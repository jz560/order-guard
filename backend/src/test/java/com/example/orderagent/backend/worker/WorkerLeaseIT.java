package com.example.orderagent.backend.worker;

import static org.assertj.core.api.Assertions.*;

import com.example.orderagent.backend.BackendApplication;
import com.example.orderagent.backend.task.TaskRecords.State;
import com.example.orderagent.backend.worker.WorkerRecords.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkerLeaseIT {
  PostgreSQLContainer<?> postgres;
  ConfigurableApplicationContext context;
  JdbcTemplate jdbc;
  WorkerTransactions tx;
  TestTaskStep step;

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
                "--agent.worker.enabled=false",
                "--agent.worker.lease-ms=30000",
                "--agent.worker.step-delay-ms=0",
                "--agent.worker.retry-ms=100",
                "--agent.worker.max-failures=2");
    jdbc = context.getBean(JdbcTemplate.class);
    tx = context.getBean(WorkerTransactions.class);
    step = context.getBean(TestTaskStep.class);
  }

  @AfterAll
  void stop() {
    if (context != null) context.close();
    if (postgres != null) postgres.close();
  }

  @BeforeEach
  void clearDedicatedTestDatabase() {
    jdbc.execute(
        "TRUNCATE task_handling,execution,approval,plan,task_step,audit_event,evidence,task_creation_request,order_guard,task");
  }

  UUID task() {
    UUID id = UUID.randomUUID();
    String order = "T05-ORDER-" + id;
    jdbc.update(
        "INSERT INTO task(id,order_id,state,created_by) VALUES(?,?,'QUEUED','operator')",
        id,
        order);
    jdbc.update(
        "INSERT INTO order_guard(order_id,task_id,reason) VALUES(?,?,'ACTIVE_TASK')", order, id);
    jdbc.update(
        "INSERT INTO evidence(id,task_id,source,business_version,payload,fetched_at) VALUES(?,?,'SIMULATOR_ORDER',1,?::jsonb,clock_timestamp())",
        UUID.randomUUID(),
        id,
        "{\"id\":\""
            + order
            + "\",\"paymentId\":\"PAY-1\",\"amountMinor\":10000,\"currency\":\"CNY\",\"version\":1,\"status\":\"PENDING_PAYMENT\",\"scenario\":\"CALLBACK_FAILED\"}");
    return id;
  }

  String state(UUID id) {
    return jdbc.queryForObject("SELECT state FROM task WHERE id=?", String.class, id);
  }

  void due(UUID id) {
    jdbc.update("UPDATE task SET next_run_at=clock_timestamp()-interval '1 second' WHERE id=?", id);
  }

  void expire(UUID id) {
    jdbc.update("UPDATE task SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", id);
  }

  @Test
  void competingWorkersCannotClaimSameLease() throws Exception {
    UUID id = task();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      var a =
          pool.submit(
              () -> {
                gate.await();
                return tx.claim("worker-a");
              });
      var b =
          pool.submit(
              () -> {
                gate.await();
                return tx.claim("worker-b");
              });
      gate.countDown();
      var claims = java.util.stream.Stream.of(a.get(), b.get()).filter(Objects::nonNull).toList();
      assertThat(claims).hasSize(1);
      assertThat(claims.get(0).taskId()).isEqualTo(id);
      assertThat(jdbc.queryForObject("SELECT lease_epoch FROM task WHERE id=?", Long.class, id))
          .isEqualTo(1);
    }
  }

  @Test
  void lockedTaskIsSkippedSoAnotherWorkerCanProgress() throws Exception {
    UUID first = task();
    try (var connection =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
      connection.setAutoCommit(false);
      try (var statement =
          connection.prepareStatement("SELECT id FROM task WHERE id=? FOR UPDATE")) {
        statement.setObject(1, first);
        statement.executeQuery().close();
      }
      UUID second = task();
      assertThat(tx.claim("other-worker").taskId()).isEqualTo(second);
      connection.rollback();
    }
  }

  @Test
  void expiredLeaseAndOldEpochCannotWriteEvenWithSameOwner() {
    UUID id = task();
    var old = tx.claim("worker-a");
    expire(id);
    assertThat(tx.complete(old, step.run(old))).isFalse();
    assertThat(tx.failed(old)).isFalse();
    var current = tx.claim("worker-a");
    assertThat(current.leaseEpoch()).isEqualTo(old.leaseEpoch() + 1);
    assertThat(tx.complete(old, step.run(old))).isFalse();
    assertThat(tx.complete(current, step.run(current))).isTrue();
    assertThat(state(id)).isEqualTo("DIAGNOSING");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task_step", Integer.class)).isEqualTo(1);
  }

  @Test
  void ownerStateAndVersionAreAlsoFenced() {
    UUID id = task();
    var claim = tx.claim("owner");
    var forged =
        new Claim(
            claim.taskId(),
            claim.orderId(),
            claim.state(),
            claim.version(),
            "other",
            claim.leaseEpoch(),
            claim.leaseUntil(),
            claim.workerFailures(),
            claim.claimedAt());
    assertThat(tx.complete(forged, step.run(forged))).isFalse();
    jdbc.update("UPDATE task SET version=version+1 WHERE id=?", id);
    assertThat(tx.complete(claim, step.run(claim))).isFalse();
    expire(id);
    var next = tx.claim("owner");
    jdbc.update("UPDATE task SET state='MANUAL' WHERE id=?", id);
    assertThat(tx.complete(next, step.run(next))).isFalse();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM task_step", Integer.class)).isZero();
  }

  @Test
  void scheduledAndApprovalWaitsReleaseLeaseAndDoNotBlockOtherTasks() {
    UUID first = task();
    var claim = tx.claim("worker");
    assertThat(tx.complete(claim, new Result(State.DIAGNOSING, "TEST_START", "测试等待下次运行", 60000)))
        .isTrue();
    assertThat(tx.claim("worker")).isNull();
    assertThat(jdbc.queryForObject("SELECT lease_owner FROM task WHERE id=?", String.class, first))
        .isNull();
    UUID second = task();
    var another = tx.claim("worker");
    assertThat(another.taskId()).isEqualTo(second);
    tx.complete(another, step.run(another));
    var finish = tx.claim("worker");
    tx.complete(finish, step.run(finish));
    assertThat(state(second)).isEqualTo("WAITING_APPROVAL");
    assertThat(tx.claim("worker")).isNull();
    assertThat(jdbc.queryForObject("SELECT next_run_at FROM task WHERE id=?", Object.class, second))
        .isNull();
    due(first);
    assertThat(tx.claim("worker").taskId()).isEqualTo(first);
  }

  @Test
  void failedStepSchedulesBoundedRetryAndRetainsOrderGuard() {
    UUID id = task();
    var first = tx.claim("worker");
    assertThat(tx.failed(first)).isTrue();
    assertThat(state(id)).isEqualTo("QUEUED");
    assertThat(
            jdbc.queryForObject(
                "SELECT next_run_at>updated_at FROM task WHERE id=?", Boolean.class, id))
        .isTrue();
    due(id);
    var second = tx.claim("worker");
    assertThat(second.workerFailures()).isEqualTo(1);
    assertThat(tx.failed(second)).isTrue();
    assertThat(state(id)).isEqualTo("MANUAL");
    assertThat(tx.claim("worker")).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM order_guard WHERE task_id=?", Integer.class, id))
        .isEqualTo(1);
    assertThat(jdbc.queryForList("SELECT status FROM task_step ORDER BY id", String.class))
        .containsExactly("RETRY", "FAILED");
  }

  @Test
  void stepWriteFailureRollsBackStateAndLeaseRelease() {
    UUID id = task();
    var claim = tx.claim("worker");
    jdbc.execute(
        "ALTER TABLE task_step ADD CONSTRAINT t07_fail_step CHECK(type <> 'TEST_START') NOT VALID");
    try {
      assertThatThrownBy(() -> tx.complete(claim, step.run(claim)))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    } finally {
      jdbc.execute("ALTER TABLE task_step DROP CONSTRAINT t07_fail_step");
    }
    assertThat(state(id)).isEqualTo("QUEUED");
    assertThat(jdbc.queryForObject("SELECT lease_owner FROM task WHERE id=?", String.class, id))
        .isEqualTo("worker");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE event_type='WORKER_ADVANCED'",
                Integer.class))
        .isZero();
    assertThat(tx.complete(claim, step.run(claim))).isTrue();
  }

  @Test
  void cancellationStopsQueuedAndDiagnosingWorkersBeforeTheyCanCreatePlan() {
    for (boolean diagnosing : List.of(false, true)) {
      UUID id = task();
      var claim = tx.claim("worker");
      if (diagnosing) {
        tx.complete(claim, step.run(claim));
        claim = tx.claim("worker");
      }
      var service = context.getBean(com.example.orderagent.backend.handling.HandlingService.class);
      service.handle(
          new com.example.orderagent.backend.security.WorkbenchAccount(
              "operator", "unused", "OPERATOR", "T05-"),
          id,
          com.example.orderagent.backend.handling.HandlingRecords.Action.CANCEL,
          claim.version(),
          "用户取消",
          null,
          UUID.randomUUID());
      assertThat(tx.complete(claim, step.run(claim))).isFalse();
      assertThat(state(id)).isEqualTo("FINISHED");
      assertThat(
              jdbc.queryForObject("SELECT count(*) FROM plan WHERE task_id=?", Integer.class, id))
          .isZero();
    }
  }

  @Test
  void unsupportedAndTerminalStatesAreNeverClaimed() {
    for (String state :
        List.of(
            "WAITING_APPROVAL",
            "APPROVED",
            "EXECUTING",
            "UNKNOWN",
            "VERIFYING",
            "MANUAL",
            "FINISHED")) {
      UUID id = task();
      jdbc.update("UPDATE task SET state=? WHERE id=?", state, id);
    }
    assertThat(tx.claim("worker")).isNull();
    UUID id = task();
    var claim = tx.claim("worker");
    assertThatThrownBy(() -> tx.complete(claim, new Result(State.EXECUTING, "BAD", "禁止跳过审批", 0)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(state(id)).isEqualTo("QUEUED");
  }

  @Test
  void planAndWaitingStateCommitTogetherAndStaleWorkerCannotCreatePlan() {
    UUID id = task();
    var first = tx.claim("worker");
    tx.complete(first, step.run(first));
    var stale = tx.claim("worker");
    expire(id);
    assertThat(tx.complete(stale, step.run(stale))).isFalse();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM plan", Integer.class)).isZero();
    var current = tx.claim("worker");
    jdbc.execute(
        "ALTER TABLE task_step ADD CONSTRAINT t08_fail_wait CHECK(type <> 'TEST_WAIT') NOT VALID");
    try {
      assertThatThrownBy(() -> tx.complete(current, step.run(current)))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    } finally {
      jdbc.execute("ALTER TABLE task_step DROP CONSTRAINT t08_fail_wait");
    }
    assertThat(state(id)).isEqualTo("DIAGNOSING");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM plan", Integer.class)).isZero();
    assertThat(tx.complete(current, step.run(current))).isTrue();
    assertThat(state(id)).isEqualTo("WAITING_APPROVAL");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM plan", Integer.class)).isEqualTo(1);
    assertThat(tx.complete(current, step.run(current))).isFalse();
  }
}
