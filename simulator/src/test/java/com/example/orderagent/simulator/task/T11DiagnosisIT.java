package com.example.orderagent.simulator.task;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class T11DiagnosisIT {
  final T06TaskIT h =
      new T06TaskIT() {
        @Override
        GenericContainer<?> process(String module) {
          var container = super.process(module);
          return module.equals("simulator")
              ? container.withEnv("SIMULATOR_EXECUTE_TOKEN", T09ExecutionIT.EXECUTE)
              : container;
        }
      };
  final List<GenericContainer<?>> workers = new ArrayList<>();

  @BeforeAll
  void start() throws Exception {
    h.start();
  }

  @AfterEach
  void stopWorkers() {
    workers.forEach(GenericContainer::close);
    workers.clear();
  }

  @AfterAll
  void stop() {
    h.stop();
  }

  GenericContainer<?> worker(int poll) {
    var w =
        h.process("backend")
            .withEnv("DIAGNOSIS_MODE", "agent")
            .withEnv("WORKER_ENABLED", "true")
            .withEnv("WORKER_POLL_MS", Integer.toString(poll))
            .withEnv("MODEL_MODE", "stub")
            .withEnv("SIMULATOR_BASE_URL", "http://simulator:8080")
            .withEnv("SIMULATOR_READ_TOKEN", T06TaskIT.READ);
    workers.add(w);
    w.start();
    return w;
  }

  UUID create(String scenario) throws Exception {
    var scene =
        h.data(
            h.client.send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(
                            h.simBase + "/internal/demo/scenarios/" + scenario + "/reset"))
                    .header("Authorization", "Bearer " + T06TaskIT.ADMIN)
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()),
            200);
    return UUID.fromString(
        h.data(h.create(scene.get("activeOrderId").asText(), h.id()), 201)
            .get("task")
            .get("id")
            .asText());
  }

  void await(UUID id, String sql, String expected) throws Exception {
    long until = System.nanoTime() + Duration.ofSeconds(35).toNanos();
    while (System.nanoTime() < until) {
      if (expected.equals(h.scalar("agent_app", sql, id))) return;
      Thread.sleep(100);
    }
    fail(
        "诊断没有达到预期："
            + expected
            + " 当前="
            + h.scalar(
                "agent_app", "SELECT state||':'||coalesce(outcome,'') FROM task WHERE id=?", id));
  }

  @Test
  void realReadToolsSupportFourOutcomesWithoutBusinessWrites() throws Exception {
    String before = h.scalar("agent_simulator", "SELECT count(*) FROM operations");
    worker(100);
    worker(100);
    var repair = create("CALLBACK_FAILED");
    await(repair, "SELECT state FROM task WHERE id=?", "WAITING_APPROVAL");
    assertThat(h.scalar("agent_app", "SELECT source FROM plan WHERE task_id=?", repair))
        .isEqualTo("AGENT_VALIDATED");
    var paid = create("ALREADY_REPAIRED");
    await(paid, "SELECT outcome FROM task WHERE id=?", "NO_ACTION");
    assertThat(h.scalar("agent_app", "SELECT count(*) FROM order_guard WHERE task_id=?", paid))
        .isEqualTo("0");
    for (String scenario : List.of("AMOUNT_CONFLICT", "MISSING_EVIDENCE", "ORDER_CLOSED")) {
      var id = create(scenario);
      await(id, "SELECT state FROM task WHERE id=?", "MANUAL");
      assertThat(h.scalar("agent_app", "SELECT count(*) FROM plan WHERE task_id=?", id))
          .isEqualTo("0");
    }
    var wait = create("NORMAL_DELAY");
    await(wait, "SELECT outcome FROM task WHERE id=?", "WAIT_RECHECK");
    assertThat(
            h.scalar(
                "agent_app",
                "SELECT lease_owner IS NULL AND next_run_at IS NOT NULL FROM task WHERE id=?",
                wait))
        .isEqualTo("t");
    await(wait, "SELECT outcome FROM task WHERE id=?", "WAIT_REVIEW_LIMIT");
    assertThat(h.scalar("agent_app", "SELECT count(*) FROM order_guard WHERE task_id=?", wait))
        .isEqualTo("1");
    assertThat(h.scalar("agent_simulator", "SELECT count(*) FROM operations")).isEqualTo(before);
    var view =
        h.data(
            h.http("GET", "/api/tasks/" + repair + "/diagnosis", "operator", null, null, false),
            200);
    assertThat(view.get("events").size()).isGreaterThan(8);
    h.data(h.http("GET", "/api/tasks/" + repair + "/diagnosis", null, null, null, false), 401);
    UUID hidden = UUID.randomUUID();
    h.update(
        "INSERT INTO task(id,order_id,state,created_by,next_run_at) VALUES(?,'OTHER-T11','QUEUED','operator',NULL)",
        hidden);
    h.data(
        h.http("GET", "/api/tasks/" + hidden + "/diagnosis", "operator", null, null, false), 404);
  }

  @Test
  void killedProcessResumesSavedEvidenceWithoutRequeryOrResettingBudget() throws Exception {
    UUID id = create("CALLBACK_FAILED");
    var first = worker(3000);
    await(id, "SELECT count(*) FROM evidence WHERE task_id=? AND source LIKE 'DIAG_%'", "4");
    first.getDockerClient().killContainerCmd(first.getContainerId()).withSignal("KILL").exec();
    // 可能退出于短事务前后；测试显式推进原租约到期，不等默认 30 秒。
    h.update(
        "UPDATE task SET lease_until=clock_timestamp()-interval '1 second' WHERE id=? AND lease_until IS NOT NULL",
        id);
    worker(100);
    await(id, "SELECT state FROM task WHERE id=?", "WAITING_APPROVAL");
    assertThat(h.scalar("agent_app", "SELECT tools FROM diagnosis_run WHERE task_id=?", id))
        .isEqualTo("4");
    assertThat(h.scalar("agent_app", "SELECT rounds FROM diagnosis_run WHERE task_id=?", id))
        .isEqualTo("2");
    assertThat(h.scalar("agent_app", "SELECT count(*) FROM plan WHERE task_id=?", id))
        .isEqualTo("1");
  }

  @Test
  void validatedPlanStillRequiresApprovalBeforeExistingExecutionFlow() throws Exception {
    worker(100);
    UUID id = create("CALLBACK_FAILED");
    await(id, "SELECT state FROM task WHERE id=?", "WAITING_APPROVAL");
    var plan =
        h.data(h.http("GET", "/api/tasks/" + id + "/plan", "approver", null, null, false), 200);
    assertThat(h.scalar("agent_app", "SELECT count(*) FROM execution WHERE task_id=?", id))
        .isEqualTo("0");
    String decision =
        h.json.writeValueAsString(
            Map.of(
                "version",
                plan.get("version").asLong(),
                "paramsHash",
                plan.get("paramsHash").asText(),
                "decision",
                "APPROVE"));
    h.data(
        h.http(
            "POST",
            "/api/plans/" + plan.get("id").asText() + "/decision",
            "approver",
            null,
            decision,
            true),
        200);
    var executor =
        h.process("backend")
            .withEnv("DIAGNOSIS_MODE", "agent")
            .withEnv("WORKER_ENABLED", "true")
            .withEnv("WORKER_POLL_MS", "100")
            .withEnv("SIMULATOR_BASE_URL", "http://simulator:8080")
            .withEnv("SIMULATOR_READ_TOKEN", T06TaskIT.READ)
            .withEnv("SIMULATOR_EXECUTE_TOKEN", T09ExecutionIT.EXECUTE);
    workers.add(executor);
    executor.start();
    await(id, "SELECT outcome FROM task WHERE id=?", "REPAIR_VERIFIED");
    assertThat(h.scalar("agent_app", "SELECT count(*) FROM execution WHERE task_id=?", id))
        .isEqualTo("1");
    assertThat(h.scalar("agent_app", "SELECT count(*) FROM order_guard WHERE task_id=?", id))
        .isEqualTo("0");
  }
}
