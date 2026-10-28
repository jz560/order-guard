package com.example.orderagent.backend.diagnosis;

import static org.assertj.core.api.Assertions.*;

import com.example.orderagent.backend.BackendApplication;
import com.example.orderagent.backend.worker.*;
import com.example.orderagent.backend.worker.WorkerRecords.Claim;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiagnosisIT {
  PostgreSQLContainer<?> pg;
  ConfigurableApplicationContext context;
  JdbcTemplate jdbc;
  DiagnosisTransactions tx;
  DiagnosisJournal journal;
  WorkerTransactions worker;

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
                "--agent.worker.enabled=false",
                "--agent.diagnosis.rounds=2",
                "--agent.diagnosis.tools=3");
    jdbc = context.getBean(JdbcTemplate.class);
    tx = context.getBean(DiagnosisTransactions.class);
    journal = context.getBean(DiagnosisJournal.class);
    worker = context.getBean(WorkerTransactions.class);
  }

  @AfterAll
  void stop() {
    if (context != null) context.close();
    if (pg != null) pg.close();
  }

  @BeforeEach
  void clear() {
    jdbc.execute(
        "TRUNCATE diagnosis_event,diagnosis_run,task_handling,execution,approval,plan,task_step,audit_event,evidence,task_creation_request,order_guard,task");
  }

  Claim task() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO task(id,order_id,state,created_by) VALUES(?,'O1','QUEUED','operator')", id);
    jdbc.update(
        "INSERT INTO order_guard(order_id,task_id,reason) VALUES('O1',?,'ACTIVE_TASK')", id);
    var c = worker.claim("first");
    assertThat(tx.start(c)).isTrue();
    return worker.claim("first");
  }

  @Test
  void interruptedCallsConsumePersistentBudgetAndCannotLoopForever() {
    var c = task();
    assertThat(tx.reserveModel(c)).isTrue();
    jdbc.update(
        "UPDATE task SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", c.taskId());
    var recovered = worker.claim("replacement");
    assertThat(tx.reserveModel(recovered)).isTrue();
    assertThat(tx.response(c, Map.of("text", "late"))).isFalse();
    tx.next(recovered);
    var last = worker.claim("replacement");
    assertThat(tx.reserveModel(last)).isFalse();
    assertThat(journal.run(c.taskId()).rounds()).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT state FROM task WHERE id=?", String.class, c.taskId()))
        .isEqualTo("MANUAL");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM order_guard", Integer.class)).isEqualTo(1);
  }

  @Test
  void repeatedReadIntentStopsAndRetainsRestriction() {
    var c = task();
    assertThat(tx.reserveModel(c)).isTrue();
    assertThat(tx.reserveTool(c, "query_order", "{}")).isTrue();
    assertThat(tx.reserveTool(c, "query_order", "{}")).isTrue();
    assertThat(tx.reserveTool(c, "query_order", "{}")).isFalse();
    assertThat(journal.run(c.taskId()).tools()).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT state FROM task WHERE id=?", String.class, c.taskId()))
        .isEqualTo("MANUAL");
  }

  @Test
  void expiredTotalDeadlineStopsBeforeModelCall() {
    var c = task();
    jdbc.update(
        "UPDATE diagnosis_run SET deadline_at=clock_timestamp()-interval '1 second' WHERE task_id=?",
        c.taskId());
    assertThat(tx.reserveModel(c)).isFalse();
    assertThat(journal.run(c.taskId()).rounds()).isZero();
  }

  @Test
  void cancelledLeaseCannotSaveEvidenceOrCreatePlan() {
    var c = task();
    tx.reserveModel(c);
    var service = context.getBean(com.example.orderagent.backend.handling.HandlingService.class);
    service.handle(
        new com.example.orderagent.backend.security.WorkbenchAccount(
            "operator", "unused", "OPERATOR", "O"),
        c.taskId(),
        com.example.orderagent.backend.handling.HandlingRecords.Action.CANCEL,
        c.version(),
        "取消",
        null,
        UUID.randomUUID());
    assertThat(tx.saveEvidence(c, "query_order", TaskEvidenceTools.JSON.readTree("{}"))).isFalse();
    assertThat(tx.next(c)).isFalse();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM plan", Integer.class)).isZero();
  }

  @Test
  void auditFailureRollsBackConclusionAndGuardRelease() {
    var c = task();
    tx.reserveModel(c);
    jdbc.execute(
        "ALTER TABLE audit_event ADD CONSTRAINT test_reject_advance CHECK(event_type<>'WORKER_ADVANCED') NOT VALID");
    try {
      assertThatThrownBy(() -> tx.stop(c, "TEST_STOP")).isInstanceOf(RuntimeException.class);
      assertThat(journal.run(c.taskId()).result()).isNull();
      assertThat(jdbc.queryForObject("SELECT state FROM task WHERE id=?", String.class, c.taskId()))
          .isEqualTo("DIAGNOSING");
    } finally {
      jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT test_reject_advance");
    }
  }
}
