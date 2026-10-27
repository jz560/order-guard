package com.example.orderagent.backend.execution;

import static org.assertj.core.api.Assertions.*;

import com.example.orderagent.backend.BackendApplication;
import com.example.orderagent.backend.approval.*;
import com.example.orderagent.backend.approval.PlanRecords.*;
import com.example.orderagent.backend.execution.ExecutionRecords.*;
import com.example.orderagent.backend.security.WorkbenchAccount;
import com.example.orderagent.backend.task.SimulatorOrderClient.Order;
import com.example.orderagent.backend.worker.WorkerRecords.Claim;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExecutionIT {
  PostgreSQLContainer<?> pg;
  ConfigurableApplicationContext context;
  JdbcTemplate jdbc;
  ExecutionTransactions tx;
  ExecutionMapper mapper;
  PlanMapper plans;
  PlanService approval;
  JsonMapper json;
  final WorkbenchAccount actor = new WorkbenchAccount("approver", "unused", "APPROVER", "T05-");

  @BeforeAll
  void start() {
    pg = new PostgreSQLContainer<>("postgres:17.6-bookworm");
    pg.start();
    context =
        new SpringApplicationBuilder(BackendApplication.class)
            .run(
                "--server.port=0",
                "--spring.profiles.active=test",
                "--spring.datasource.url=" + pg.getJdbcUrl(),
                "--spring.datasource.username=" + pg.getUsername(),
                "--spring.datasource.password=" + pg.getPassword(),
                "--agent.worker.enabled=false");
    jdbc = context.getBean(JdbcTemplate.class);
    tx = context.getBean(ExecutionTransactions.class);
    mapper = context.getBean(ExecutionMapper.class);
    plans = context.getBean(PlanMapper.class);
    approval = context.getBean(PlanService.class);
    json = context.getBean(JsonMapper.class);
  }

  @AfterAll
  void stop() {
    if (context != null) context.close();
    if (pg != null) pg.close();
  }

  @BeforeEach
  void clear() {
    jdbc.execute(
        "TRUNCATE execution,approval,plan,task_step,audit_event,evidence,task_creation_request,order_guard,task");
  }

  PlanRow plan(boolean approve, boolean expired) {
    UUID task = UUID.randomUUID(), plan = UUID.randomUUID(), evidence = UUID.randomUUID();
    String order = "T05-" + task;
    var p =
        new Params(Action.VERIFY_PAYMENT_AND_ADVANCE_ORDER, 10000, "CNY", 1, order, "PAY-" + task);
    String params = json.writeValueAsString(p);
    jdbc.update(
        "INSERT INTO task(id,order_id,state,created_by,next_run_at) VALUES(?,?,'WAITING_APPROVAL','operator',NULL)",
        task,
        order);
    jdbc.update(
        "INSERT INTO order_guard(order_id,task_id,reason) VALUES(?,?,'ACTIVE_TASK')", order, task);
    jdbc.update(
        "INSERT INTO evidence(id,task_id,source,business_version,payload,fetched_at) VALUES(?,?,'SIMULATOR_ORDER',1,'{}',clock_timestamp())",
        evidence,
        task);
    jdbc.update(
        "INSERT INTO plan(id,task_id,version,action,canonical_params,params_hash,evidence_id,source,created_at,expires_at) VALUES(?,?,1,'VERIFY_PAYMENT_AND_ADVANCE_ORDER',?,?,?,'TEST_STUB',clock_timestamp()-interval '20 minutes',clock_timestamp()+?*interval '1 second')",
        plan,
        task,
        params,
        PlanService.hash(params),
        evidence,
        expired ? -1 : 900);
    var row = plans.plan(plan);
    if (approve)
      approval.decide(actor, plan, 1, row.paramsHash(), Decision.APPROVE, null, UUID.randomUUID());
    return row;
  }

  Params params(PlanRow p) {
    return tx.params(
        p, jdbc.queryForObject("SELECT order_id FROM task WHERE id=?", String.class, p.taskId()));
  }

  Facts facts(Params p, boolean paid) {
    return new Facts(
        new Order(
            p.orderId(),
            "CALLBACK_FAILED",
            paid ? "PAID" : "PENDING_PAYMENT",
            p.amountMinor(),
            p.currency(),
            p.paymentId(),
            paid ? 2L : 1L),
        new Payment(
            p.paymentId(), p.orderId(), "SUCCESS", p.amountMinor(), p.currency(), Instant.now()),
        Instant.now());
  }

  Operation success(Started s) {
    var p = s.params();
    return new Operation(
        s.execution().operationId().toString(),
        p.orderId(),
        p.paymentId(),
        p.amountMinor(),
        p.currency(),
        1,
        p.action(),
        "SUCCEEDED",
        "VERIFIED_AND_ADVANCED",
        "成功",
        1,
        2,
        Instant.now());
  }

  String state(UUID task) {
    return jdbc.queryForObject("SELECT state FROM task WHERE id=?", String.class, task);
  }

  void expireLease(UUID task) {
    jdbc.update(
        "UPDATE task SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", task);
  }

  void due(UUID task) {
    jdbc.update(
        "UPDATE task SET next_run_at=clock_timestamp()-interval '1 second' WHERE id=?", task);
  }

  @Test
  void racingClaimsAndRepeatedStartCreateOnlyOneImmutableIntent() throws Exception {
    var p = plan(true, false);
    var params = params(p);
    List<Claim> claims;
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      var a =
          pool.submit(
              () -> {
                gate.await();
                return tx.claim("a");
              });
      var b =
          pool.submit(
              () -> {
                gate.await();
                return tx.claim("b");
              });
      gate.countDown();
      claims = java.util.stream.Stream.of(a.get(), b.get()).filter(Objects::nonNull).toList();
    }
    assertThat(claims).hasSize(1);
    var c = claims.getFirst();
    var started = tx.start(c, p.id(), facts(params, false));
    assertThat(started).isNotNull();
    assertThat(tx.start(c, p.id(), facts(params, false))).isNull();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM execution", Integer.class)).isEqualTo(1);
    assertThatThrownBy(() -> jdbc.update("UPDATE execution SET operation_id=?", UUID.randomUUID()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("DELETE FROM execution"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void unapprovedRejectedAndExpiredPlansCannotStart() {
    var p = plan(false, true);
    assertThat(tx.claim("worker")).isNull();
    jdbc.update("UPDATE task SET state='APPROVED' WHERE id=?", p.taskId());
    var claim = tx.claim("worker");
    assertThat(tx.start(claim, p.id(), facts(params(p), false))).isNull();
    assertThat(mapper.execution(p.taskId())).isNull();
    var rejected = plan(false, false);
    approval.decide(
        actor, rejected.id(), 1, rejected.paramsHash(), Decision.REJECT, "拒绝", UUID.randomUUID());
    assertThat(tx.claim("worker")).isNull();
    var expired = plan(false, true);
    jdbc.update(
        "INSERT INTO approval(id,plan_id,decision,approver_id) VALUES(?,?,'APPROVE','approver')",
        UUID.randomUUID(),
        expired.id());
    jdbc.update("UPDATE task SET state='APPROVED' WHERE id=?", expired.taskId());
    var c = tx.claim("worker");
    assertThat(tx.start(c, expired.id(), facts(params(expired), false))).isNull();
    assertThat(mapper.execution(expired.taskId())).isNull();
  }

  @Test
  void changedFactsPreventIntentAndKeepEvidence() {
    var p = plan(true, false);
    var c = tx.claim("worker");
    assertThat(tx.start(c, p.id(), facts(params(p), true))).isNull();
    assertThat(state(p.taskId())).isEqualTo("MANUAL");
    assertThat(mapper.execution(p.taskId())).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM evidence WHERE source='EXECUTION_PRECHECK'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void staleOwnerCannotStartOrOverwriteRecoveredResult() {
    var p = plan(true, false);
    var c = tx.claim("worker");
    expireLease(p.taskId());
    assertThat(tx.start(c, p.id(), facts(params(p), false))).isNull();
    var current = tx.claim("worker");
    var started = tx.start(current, p.id(), facts(params(p), false));
    expireLease(p.taskId());
    var recovery = tx.claim("new-worker");
    tx.received(started.claim(), started.params(), success(started));
    assertThat(state(p.taskId())).isEqualTo("EXECUTING");
    tx.received(recovery, started.params(), success(started));
    assertThat(state(p.taskId())).isEqualTo("VERIFYING");
    var verify = tx.claim("worker");
    tx.verified(verify, started.params(), success(started), facts(started.params(), true));
    assertThat(state(p.taskId())).isEqualTo("FINISHED");
    assertThat(mapper.execution(p.taskId()).status()).isEqualTo(Status.SUCCEEDED);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM order_guard", Integer.class)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM evidence WHERE source='EXECUTION_VERIFICATION'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void successResponseAloneDoesNotMeanBusinessSuccess() {
    var p = plan(true, false);
    var started = tx.start(tx.claim("worker"), p.id(), facts(params(p), false));
    tx.received(started.claim(), started.params(), success(started));
    assertThat(state(p.taskId())).isEqualTo("VERIFYING");
    tx.verified(
        tx.claim("worker"), started.params(), success(started), facts(started.params(), false));
    assertThat(state(p.taskId())).isEqualTo("MANUAL");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM order_guard", Integer.class)).isEqualTo(1);
  }

  @Test
  void missingResultStaysUnknownWithSameIntent() {
    var p = plan(true, false);
    var s = tx.start(tx.claim("worker"), p.id(), facts(params(p), false));
    tx.pending(s.claim(), "NOT_FOUND");
    assertThat(state(p.taskId())).isEqualTo("UNKNOWN");
    due(p.taskId());
    tx.pending(tx.claim("worker"), "NOT_FOUND");
    assertThat(mapper.execution(p.taskId()).operationId()).isEqualTo(s.execution().operationId());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM execution", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM order_guard", Integer.class)).isEqualTo(1);
  }

  @Test
  void expiredConfirmationRetainsGuardAndDoesNotClaimBusinessFailure() {
    var p = plan(true, false);
    jdbc.update(
        "INSERT INTO execution(operation_id,task_id,plan_id,params_hash,status,created_at,deadline_at) VALUES(?,?,?,?,'UNKNOWN',clock_timestamp()-interval '6 minutes',clock_timestamp()-interval '1 second')",
        UUID.randomUUID(),
        p.taskId(),
        p.id(),
        p.paramsHash());
    jdbc.update(
        "UPDATE task SET state='UNKNOWN',next_run_at=clock_timestamp() WHERE id=?", p.taskId());
    tx.pending(tx.claim("worker"), "NOT_FOUND");
    assertThat(state(p.taskId())).isEqualTo("MANUAL");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM order_guard", Integer.class)).isEqualTo(1);
  }

  @Test
  void auditFailureRollsBackIntentAndState() {
    var p = plan(true, false);
    var c = tx.claim("worker");
    jdbc.execute(
        "ALTER TABLE audit_event ADD CONSTRAINT t09_fail CHECK(event_type<>'EXECUTION_INTENT') NOT VALID");
    try {
      assertThatThrownBy(() -> tx.start(c, p.id(), facts(params(p), false)))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    } finally {
      jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT t09_fail");
    }
    assertThat(state(p.taskId())).isEqualTo("APPROVED");
    assertThat(mapper.execution(p.taskId())).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM evidence WHERE source='EXECUTION_PRECHECK'", Integer.class))
        .isZero();
  }
}
