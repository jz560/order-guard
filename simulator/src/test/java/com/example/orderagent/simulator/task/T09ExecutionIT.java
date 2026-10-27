package com.example.orderagent.simulator.task;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.*;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class T09ExecutionIT {
  static final String EXECUTE = UUID.randomUUID().toString();
  final T06TaskIT h =
      new T06TaskIT() {
        @Override
        GenericContainer<?> process(String module) {
          var container = super.process(module);
          return module.equals("simulator")
              ? container.withEnv("SIMULATOR_EXECUTE_TOKEN", EXECUTE)
              : container;
        }
      };
  GenericContainer<?> diagnosis;
  final List<GenericContainer<?>> workers = new ArrayList<>();

  @BeforeAll
  void start() throws Exception {
    h.start();
    diagnosis =
        h.process("backend")
            .withEnv("WORKER_ENABLED", "true")
            .withEnv("WORKER_POLL_MS", "100")
            .withEnv("WORKER_STEP_DELAY_MS", "0");
    diagnosis.start();
  }

  @AfterEach
  void stopWorkers() {
    workers.forEach(GenericContainer::close);
    workers.clear();
  }

  @AfterAll
  void stop() {
    if (diagnosis != null) diagnosis.close();
    h.stop();
  }

  GenericContainer<?> worker() {
    var worker =
        h.process("backend")
            .withEnv("WORKER_ENABLED", "true")
            .withEnv("WORKER_POLL_MS", "100")
            .withEnv("EXECUTION_HTTP_TIMEOUT_MS", "500")
            .withEnv("SIMULATOR_BASE_URL", "http://simulator:8080")
            .withEnv("SIMULATOR_READ_TOKEN", T06TaskIT.READ)
            .withEnv("SIMULATOR_EXECUTE_TOKEN", EXECUTE);
    workers.add(worker);
    worker.start();
    return worker;
  }

  void await(Callable<Boolean> condition) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.call()) return;
      Thread.sleep(80);
    }
    fail("等待持久化执行状态超时");
  }

  JsonNode plan() throws Exception {
    String task = h.data(h.create(h.order(), h.id()), 201).get("task").get("id").asText();
    await(
        () ->
            "WAITING_APPROVAL"
                .equals(
                    h.scalar(
                        "agent_app", "SELECT state FROM task WHERE id=?", UUID.fromString(task))));
    return h.data(
        h.http("GET", "/api/tasks/" + task + "/plan", "approver", null, null, false), 200);
  }

  UUID task(JsonNode p) {
    return UUID.fromString(p.get("taskId").asText());
  }

  String order(JsonNode p) {
    return p.get("params").get("orderId").asText();
  }

  void approve(JsonNode p) throws Exception {
    String body =
        h.json.writeValueAsString(
            Map.of(
                "version",
                p.get("version").asLong(),
                "paramsHash",
                p.get("paramsHash").asText(),
                "decision",
                "APPROVE"));
    h.data(
        h.http(
            "POST",
            "/api/plans/" + p.get("id").asText() + "/decision",
            "approver",
            null,
            body,
            true),
        200);
  }

  void state(JsonNode p, String state) throws Exception {
    await(() -> state.equals(h.scalar("agent_app", "SELECT state FROM task WHERE id=?", task(p))));
  }

  String countOperations(JsonNode p) throws Exception {
    return h.scalar(
        "agent_simulator", "SELECT count(*) FROM operations WHERE order_id=?", order(p));
  }

  JsonNode execution(JsonNode p) throws Exception {
    return h.data(
        h.http("GET", "/api/tasks/" + task(p) + "/execution", "operator", null, null, false), 200);
  }

  void simulatorUpdate(String sql, Object... values) throws Exception {
    try (var connection = h.db("agent_simulator");
        var statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
      statement.executeUpdate();
    }
  }

  @Test
  void twoWorkersCompleteOneRepairWithVerificationEvidence() throws Exception {
    var p = plan();
    approve(p);
    worker();
    worker();
    state(p, "FINISHED");
    assertThat(h.scalar("agent_app", "SELECT outcome FROM task WHERE id=?", task(p)))
        .isEqualTo("REPAIR_VERIFIED");
    assertThat(countOperations(p)).isEqualTo("1");
    assertThat(h.scalar("agent_simulator", "SELECT version FROM orders WHERE id=?", order(p)))
        .isEqualTo("2");
    assertThat(
            h.scalar(
                "agent_app",
                "SELECT count(*) FROM evidence WHERE task_id=? AND source='EXECUTION_VERIFICATION'",
                task(p)))
        .isEqualTo("1");
    assertThat(h.scalar("agent_app", "SELECT count(*) FROM order_guard WHERE task_id=?", task(p)))
        .isEqualTo("0");
    assertThat(execution(p).get("status").asText()).isEqualTo("SUCCEEDED");
    approve(p);
    execution(p);
    assertThat(countOperations(p)).isEqualTo("1");
    h.data(h.http("GET", "/api/tasks/" + task(p) + "/execution", null, null, null, false), 401);
    UUID hidden = UUID.randomUUID();
    h.update(
        "INSERT INTO task(id,order_id,state,created_by,next_run_at) VALUES(?,'OTHER-T09','FINISHED','operator',NULL)",
        hidden);
    h.data(
        h.http("GET", "/api/tasks/" + hidden + "/execution", "operator", null, null, false), 404);
  }

  @Test
  void changedOrderBlocksBeforeAnyWrite() throws Exception {
    var p = plan();
    simulatorUpdate("UPDATE orders SET status='CLOSED',version=version+1 WHERE id=?", order(p));
    approve(p);
    worker();
    state(p, "MANUAL");
    assertThat(countOperations(p)).isEqualTo("0");
    assertThat(h.scalar("agent_app", "SELECT count(*) FROM execution WHERE task_id=?", task(p)))
        .isEqualTo("0");
    assertThat(h.scalar("agent_app", "SELECT outcome FROM task WHERE id=?", task(p)))
        .isEqualTo("BUSINESS_FACTS_CHANGED");
  }

  @Test
  void lostResponseBecomesUnknownThenQueriesAndVerifies() throws Exception {
    var p = plan();
    var response =
        h.client.send(
            HttpRequest.newBuilder(
                    URI.create(h.simBase + "/internal/demo/scenarios/CALLBACK_FAILED/fault"))
                .header("Authorization", "Bearer " + T06TaskIT.ADMIN)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"fault\":\"DELAY_RESPONSE_ONCE\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    h.data(response, 200);
    approve(p);
    worker();
    state(p, "FINISHED");
    assertThat(
            h.scalar(
                "agent_app",
                "SELECT count(*) FROM task_step WHERE task_id=? AND after_state='UNKNOWN'",
                task(p)))
        .isEqualTo("1");
    assertThat(countOperations(p)).isEqualTo("1");
    assertThat(execution(p).get("status").asText()).isEqualTo("SUCCEEDED");
  }

  @Test
  void absentOperationAfterPersistedIntentIsNeverResent() throws Exception {
    var p = plan();
    approve(p);
    UUID op = UUID.randomUUID();
    // 构造“意图已提交、请求尚未发出”恢复位置；此案例不是进程退出实验。
    h.update(
        "INSERT INTO execution(operation_id,task_id,plan_id,params_hash,status) VALUES(?,?,?,?,'EXECUTING')",
        op,
        task(p),
        UUID.fromString(p.get("id").asText()),
        p.get("paramsHash").asText());
    h.update("UPDATE task SET state='EXECUTING',next_run_at=clock_timestamp() WHERE id=?", task(p));
    worker();
    await(
        () ->
            Integer.parseInt(
                    h.scalar("agent_app", "SELECT checks FROM execution WHERE task_id=?", task(p)))
                >= 2);
    assertThat(h.scalar("agent_app", "SELECT state FROM task WHERE id=?", task(p)))
        .isEqualTo("UNKNOWN");
    assertThat(execution(p).get("operationId").asText()).isEqualTo(op.toString());
    assertThat(countOperations(p)).isEqualTo("0");
    assertThat(h.scalar("agent_app", "SELECT count(*) FROM order_guard WHERE task_id=?", task(p)))
        .isEqualTo("1");
  }

  @Test
  void downstreamDefinitiveRejectionIsRecordedAndReleasesGuard() throws Exception {
    var p = plan();
    h.order(); // 原订单事实未变，但已不属于当前演示轮次，由下游原子检查拒绝。
    approve(p);
    worker();
    state(p, "FINISHED");
    assertThat(execution(p).get("status").asText()).isEqualTo("REJECTED");
    assertThat(execution(p).get("result").get("code").asText()).isEqualTo("SCENARIO_REPLACED");
    assertThat(h.scalar("agent_app", "SELECT count(*) FROM order_guard WHERE task_id=?", task(p)))
        .isEqualTo("0");
    assertThat(h.scalar("agent_simulator", "SELECT version FROM orders WHERE id=?", order(p)))
        .isEqualTo("1");
  }

  @Test
  void killedCallerAfterDownstreamCommitRecoversOriginalOperation() throws Exception {
    var p = plan();
    approve(p);
    h.update(
        "ALTER TABLE task_step ADD CONSTRAINT t09_block_result CHECK(type<>'CONTROLLED_EXECUTION') NOT VALID");
    try {
      var first = worker();
      await(() -> countOperations(p).equals("1"));
      String operation =
          h.scalar("agent_app", "SELECT operation_id FROM execution WHERE task_id=?", task(p));
      assertThat(h.scalar("agent_app", "SELECT state FROM task WHERE id=?", task(p)))
          .isEqualTo("EXECUTING");
      DockerClientFactory.instance()
          .client()
          .killContainerCmd(first.getContainerId())
          .withSignal("KILL")
          .exec();
      first.close();
      workers.remove(first);
      h.update("ALTER TABLE task_step DROP CONSTRAINT t09_block_result");
      // 缩短测试等待；仍须由新 Worker 取得更大的 epoch，未绕过写回条件。
      h.update(
          "UPDATE task SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", task(p));
      worker();
      state(p, "FINISHED");
      assertThat(execution(p).get("operationId").asText()).isEqualTo(operation);
      assertThat(execution(p).get("status").asText()).isEqualTo("SUCCEEDED");
      assertThat(countOperations(p)).isEqualTo("1");
      assertThat(h.scalar("agent_simulator", "SELECT version FROM orders WHERE id=?", order(p)))
          .isEqualTo("2");
    } finally {
      h.update("ALTER TABLE task_step DROP CONSTRAINT IF EXISTS t09_block_result");
    }
  }
}
