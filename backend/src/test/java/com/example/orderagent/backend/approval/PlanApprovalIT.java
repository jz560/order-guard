package com.example.orderagent.backend.approval;

import static org.assertj.core.api.Assertions.*;

import com.example.orderagent.backend.BackendApplication;
import com.example.orderagent.backend.api.ApiException;
import com.example.orderagent.backend.approval.PlanRecords.*;
import com.example.orderagent.backend.security.WorkbenchAccount;
import com.example.orderagent.backend.worker.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanApprovalIT {
  PostgreSQLContainer<?> postgres;
  ConfigurableApplicationContext context;
  JdbcTemplate jdbc;
  PlanService service;
  PlanMapper mapper;
  final WorkbenchAccount approver = new WorkbenchAccount("approver", "unused", "APPROVER", "T05-");

  @BeforeAll
  void start() {
    postgres = new PostgreSQLContainer<>("postgres:17.6-bookworm");
    postgres.start();
    context =
        new SpringApplicationBuilder(BackendApplication.class)
            .run(
                "--server.port=0",
                "--spring.profiles.active=test",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--agent.worker.enabled=false");
    jdbc = context.getBean(JdbcTemplate.class);
    service = context.getBean(PlanService.class);
    mapper = context.getBean(PlanMapper.class);
  }

  @AfterAll
  void stop() {
    if (context != null) context.close();
    if (postgres != null) postgres.close();
  }

  PlanRow plan(boolean expired) {
    UUID task = UUID.randomUUID(), id = UUID.randomUUID(), evidence = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO task(id,order_id,state,created_by,next_run_at) VALUES(?,?,'WAITING_APPROVAL','operator',NULL)",
        task,
        "T05-" + task);
    jdbc.update(
        "INSERT INTO order_guard(order_id,task_id,reason) VALUES(?,?,'ACTIVE_TASK')",
        "T05-" + task,
        task);
    jdbc.update(
        "INSERT INTO evidence(id,task_id,source,business_version,payload,fetched_at) VALUES(?,?,'SIMULATOR_ORDER',1,'{}',clock_timestamp())",
        evidence,
        task);
    jdbc.update(
        "INSERT INTO plan(id,task_id,version,action,canonical_params,params_hash,evidence_id,source,created_at,expires_at) VALUES(?,?,1,'VERIFY_PAYMENT_AND_ADVANCE_ORDER','{}',?,?,'TEST_STUB',clock_timestamp()-interval '20 minutes',clock_timestamp()+ (? * interval '1 second'))",
        id,
        task,
        PlanService.hash("{}"),
        evidence,
        expired ? -1 : 900);
    return mapper.plan(id);
  }

  PlanRow decide(PlanRow plan, Decision decision) {
    return service.decide(
        approver,
        plan.id(),
        plan.version(),
        plan.paramsHash(),
        decision,
        decision == Decision.REJECT ? "拒绝测试" : null,
        UUID.randomUUID());
  }

  String state(PlanRow plan) {
    return jdbc.queryForObject("SELECT state FROM task WHERE id=?", String.class, plan.taskId());
  }

  long count(String table, String field, UUID id) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM " + table + " WHERE " + field + "=?", Long.class, id);
  }

  @Test
  void concurrentDuplicatesHaveOneDecisionAndOneStateTransition() throws Exception {
    var p = plan(false);
    try (var pool = Executors.newFixedThreadPool(8)) {
      var gate = new CountDownLatch(1);
      List<Future<PlanRow>> results = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        results.add(
            pool.submit(
                () -> {
                  gate.await();
                  return decide(p, Decision.APPROVE);
                }));
      gate.countDown();
      for (var f : results) assertThat(f.get().id()).isEqualTo(p.id());
    }
    assertThat(state(p)).isEqualTo("APPROVED");
    assertThat(count("approval", "plan_id", p.id())).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT version FROM task WHERE id=?", Long.class, p.taskId()))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE task_id=? AND event_type='PLAN_APPROVED'",
                Integer.class,
                p.taskId()))
        .isEqualTo(1);
    assertThat(count("order_guard", "task_id", p.taskId())).isEqualTo(1);
    assertThat(context.getBean(WorkerTransactions.class).claim("test-worker")).isNull();
  }

  @Test
  void approvalAndRejectionRaceHasSingleWinner() throws Exception {
    var p = plan(false);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      List<Future<Integer>> results = new ArrayList<>();
      for (var d : Decision.values())
        results.add(
            pool.submit(
                () -> {
                  gate.await();
                  try {
                    decide(p, d);
                    return 200;
                  } catch (ApiException e) {
                    return e.status();
                  }
                }));
      gate.countDown();
      assertThat(List.of(results.get(0).get(), results.get(1).get()))
          .containsExactlyInAnyOrder(200, 409);
    }
    assertThat(count("approval", "plan_id", p.id())).isEqualTo(1);
    assertThat(state(p))
        .isEqualTo(
            mapper.approval(p.id()).decision() == Decision.APPROVE ? "APPROVED" : "FINISHED");
  }

  @Test
  void frozenParamsAndApprovalCannotBeEditedOrDeleted() {
    var p = plan(false);
    decide(p, Decision.APPROVE);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE plan SET canonical_params='{}',version=version+1 WHERE id=?", p.id()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE plan SET expires_at=expires_at+interval '1 hour' WHERE id=?", p.id()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("DELETE FROM approval WHERE plan_id=?", p.id()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE approval SET decision='REJECT',reason='改判' WHERE plan_id=?", p.id()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void staleVersionHashAndOlderPlanCannotAuthorize() {
    var p = plan(false);
    assertThatThrownBy(
            () ->
                service.decide(
                    approver, p.id(), 2, p.paramsHash(), Decision.APPROVE, null, UUID.randomUUID()))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                service.decide(
                    approver, p.id(), 1, "0".repeat(64), Decision.APPROVE, null, UUID.randomUUID()))
        .isInstanceOf(ApiException.class);
    jdbc.update(
        "INSERT INTO plan(id,task_id,version,action,canonical_params,params_hash,evidence_id,source) SELECT ?,task_id,2,action,'{\"changed\":true}',?,evidence_id,source FROM plan WHERE id=?",
        UUID.randomUUID(),
        PlanService.hash("{\"changed\":true}"),
        p.id());
    assertThatThrownBy(() -> decide(p, Decision.APPROVE)).isInstanceOf(ApiException.class);
    assertThat(count("approval", "plan_id", p.id())).isZero();
    assertThat(state(p)).isEqualTo("WAITING_APPROVAL");
  }

  @Test
  void expiredAndRejectedPlansNeverBecomeRunnable() {
    var expired = plan(true);
    assertThatThrownBy(() -> decide(expired, Decision.APPROVE)).isInstanceOf(ApiException.class);
    service.expireOne();
    assertThat(state(expired)).isEqualTo("FINISHED");
    assertThat(count("order_guard", "task_id", expired.taskId())).isZero();
    var rejected = plan(false);
    decide(rejected, Decision.REJECT);
    assertThatThrownBy(() -> decide(rejected, Decision.APPROVE)).isInstanceOf(ApiException.class);
    assertThat(state(rejected)).isEqualTo("FINISHED");
    assertThat(count("order_guard", "task_id", rejected.taskId())).isZero();
    assertThat(context.getBean(WorkerTransactions.class).claim("worker")).isNull();
  }

  @Test
  void expiredApprovalIsPreservedButCannotRestartTask() {
    var p = plan(true);
    // 模拟服务停机期间，已批准方案超过有效期的持久化状态。
    jdbc.update(
        "INSERT INTO approval(id,plan_id,decision,approver_id) VALUES(?,?,'APPROVE','approver')",
        UUID.randomUUID(),
        p.id());
    jdbc.update("UPDATE task SET state='APPROVED' WHERE id=?", p.taskId());
    service.expireOne();
    assertThat(state(p)).isEqualTo("FINISHED");
    decide(p, Decision.APPROVE);
    assertThat(state(p)).isEqualTo("FINISHED");
    assertThat(count("approval", "plan_id", p.id())).isEqualTo(1);
  }

  @Test
  void failedAuditRollsBackDecisionTaskAndGuard() {
    var p = plan(false);
    jdbc.execute(
        "ALTER TABLE audit_event ADD CONSTRAINT t08_fail CHECK(event_type <> 'PLAN_REJECTED') NOT VALID");
    try {
      assertThatThrownBy(() -> decide(p, Decision.REJECT))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    } finally {
      jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT t08_fail");
    }
    assertThat(state(p)).isEqualTo("WAITING_APPROVAL");
    assertThat(mapper.approval(p.id())).isNull();
    assertThat(count("order_guard", "task_id", p.taskId())).isEqualTo(1);
  }
}
