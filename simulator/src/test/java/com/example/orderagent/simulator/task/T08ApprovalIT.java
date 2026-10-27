package com.example.orderagent.simulator.task;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class T08ApprovalIT {
  final T06TaskIT h = new T06TaskIT();
  GenericContainer<?> worker;

  @BeforeAll
  void start() throws Exception {
    h.start();
    worker =
        h.process("backend")
            .withEnv("WORKER_ENABLED", "true")
            .withEnv("WORKER_POLL_MS", "100")
            .withEnv("WORKER_STEP_DELAY_MS", "0");
    worker.start();
  }

  @AfterAll
  void stop() {
    if (worker != null) worker.close();
    h.stop();
  }

  JsonNode plan() throws Exception {
    String id = h.data(h.create(h.order(), h.id()), 201).get("task").get("id").asText();
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline) {
      var r = h.http("GET", "/api/tasks/" + id + "/plan", "approver", null, null, false);
      if (r.statusCode() == 200) return h.data(r, 200);
      h.data(r, 404);
      Thread.sleep(100);
    }
    throw new AssertionError("Worker 没有生成方案");
  }

  String body(JsonNode p, String decision, String reason) {
    return h.json.writeValueAsString(
        java.util.Map.of(
            "version",
            p.get("version").asLong(),
            "paramsHash",
            p.get("paramsHash").asText(),
            "decision",
            decision,
            "reason",
            reason));
  }

  JsonNode decide(JsonNode p, String body, int status) throws Exception {
    return h.data(
        h.http(
            "POST",
            "/api/plans/" + p.get("id").asText() + "/decision",
            "approver",
            null,
            body,
            true),
        status);
  }

  @Test
  void frozenPlanIsVisibleAndOnlyScopedApproverCanDecide() throws Exception {
    var p = plan();
    String url = "/api/plans/" + p.get("id").asText();
    assertThat(p.get("source").asText()).isEqualTo("TEST_STUB");
    assertThat(p.get("allowedActions").toString()).contains("APPROVE", "REJECT");
    assertThat(p.get("params").get("amountMinor").asLong()).isEqualTo(10000);
    assertThat(p.get("paramsHash").asText()).matches("[0-9a-f]{64}");
    var operator = h.data(h.http("GET", url, "operator", null, null, false), 200);
    assertThat(operator.get("allowedActions").size()).isZero();
    h.data(h.http("GET", url, null, null, null, false), 401);
    String body = body(p, "APPROVE", "");
    h.data(h.http("POST", url + "/decision", "operator", null, body, true), 403);
    h.data(h.http("POST", url + "/decision", "approver", null, body, false), 403);
    decide(p, body.substring(0, body.length() - 1) + ",\"approverId\":\"operator\"}", 400);
    decide(p, body.replace(p.get("paramsHash").asText(), "0".repeat(64)), 409);
    decide(p, body.replace("\"version\":1", "\"version\":2"), 409);
    UUID hiddenTask = UUID.randomUUID(),
        hiddenPlan = UUID.randomUUID(),
        hiddenEvidence = UUID.randomUUID();
    h.update(
        "INSERT INTO task(id,order_id,state,created_by,next_run_at) VALUES(?,?,'WAITING_APPROVAL','operator',NULL)",
        hiddenTask,
        "OTHER-" + h.id());
    h.update(
        "INSERT INTO evidence(id,task_id,source,business_version,payload,fetched_at) VALUES(?,?,'SIMULATOR_ORDER',1,'{}',clock_timestamp())",
        hiddenEvidence,
        hiddenTask);
    h.update(
        "INSERT INTO plan(id,task_id,version,action,canonical_params,params_hash,evidence_id,source) SELECT ?,?,version,action,canonical_params,params_hash,?,source FROM plan WHERE id=?",
        hiddenPlan,
        hiddenTask,
        hiddenEvidence,
        UUID.fromString(p.get("id").asText()));
    h.data(h.http("GET", "/api/plans/" + hiddenPlan, "approver", null, null, false), 404);
    h.data(
        h.http("POST", "/api/plans/" + hiddenPlan + "/decision", "approver", null, body, true),
        404);
  }

  @Test
  void repeatedApprovalPersistsAcrossRestartWithoutExecution() throws Exception {
    var p = plan();
    String body = body(p, "APPROVE", "");
    var accepted = decide(p, body, 200);
    assertThat(accepted.get("taskState").asText()).isEqualTo("APPROVED");
    assertThat(accepted.get("approval").get("approverId").asText()).isEqualTo("approver");
    assertThat(accepted.get("allowedActions").size()).isZero();
    h.backend.close();
    h.bootBackend("test");
    assertThat(decide(p, body, 200).get("approval")).isEqualTo(accepted.get("approval"));
    decide(p, body(p, "REJECT", "改判"), 409);
    assertThat(
            h.scalar(
                "agent_app",
                "SELECT count(*) FROM approval WHERE plan_id=?",
                UUID.fromString(p.get("id").asText())))
        .isEqualTo("1");
    assertThat(h.scalar("agent_simulator", "SELECT count(*) FROM operations")).isEqualTo("0");
  }

  @Test
  void rejectionRequiresReasonAndFinishesWithoutWrite() throws Exception {
    var p = plan();
    decide(p, body(p, "REJECT", "   "), 400);
    var rejected = decide(p, body(p, "REJECT", "证据待补充"), 200);
    assertThat(rejected.get("taskState").asText()).isEqualTo("FINISHED");
    assertThat(decide(p, body(p, "REJECT", "证据待补充"), 200).get("approval"))
        .isEqualTo(rejected.get("approval"));
    decide(p, body(p, "APPROVE", ""), 409);
    assertThat(
            h.scalar(
                "agent_app",
                "SELECT count(*) FROM order_guard WHERE task_id=?",
                UUID.fromString(p.get("taskId").asText())))
        .isEqualTo("0");
  }

  @Test
  void expiredPlanIsReadOnlyAndCannotBeApproved() throws Exception {
    var p = plan();
    UUID task = UUID.randomUUID(), plan = UUID.randomUUID(), evidence = UUID.randomUUID();
    h.update(
        "INSERT INTO task(id,order_id,state,created_by,next_run_at) VALUES(?,?,'WAITING_APPROVAL','operator',NULL)",
        task,
        "T05-ORDER-" + task);
    h.update(
        "INSERT INTO evidence(id,task_id,source,business_version,payload,fetched_at) VALUES(?,?,'SIMULATOR_ORDER',1,'{}',clock_timestamp())",
        evidence,
        task);
    h.update(
        "INSERT INTO plan(id,task_id,version,action,canonical_params,params_hash,evidence_id,source,created_at,expires_at) SELECT ?,?,version,action,canonical_params,params_hash,?,source,clock_timestamp()-interval '20 minutes',clock_timestamp()-interval '1 second' FROM plan WHERE id=?",
        plan,
        task,
        evidence,
        UUID.fromString(p.get("id").asText()));
    var expired = h.data(h.http("GET", "/api/plans/" + plan, "approver", null, null, false), 200);
    assertThat(expired.get("expired").asBoolean()).isTrue();
    assertThat(expired.get("allowedActions").size()).isZero();
    assertThat(decide(expired, body(expired, "APPROVE", ""), 409).get("code").asText())
        .isEqualTo("PLAN_EXPIRED");
    assertThat(h.scalar("agent_app", "SELECT count(*) FROM approval WHERE plan_id=?", plan))
        .isEqualTo("0");
  }
}
