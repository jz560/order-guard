package com.example.orderagent.simulator.task;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class T10HandlingIT {
  final T09ExecutionIT fixture = new T09ExecutionIT();
  final T06TaskIT h = fixture.h;

  @BeforeAll
  void start() throws Exception {
    fixture.start();
  }

  @AfterEach
  void stopWorkers() {
    fixture.stopWorkers();
  }

  @AfterAll
  void stop() {
    fixture.stop();
  }

  JsonNode task(JsonNode p) throws Exception {
    return h.data(
        h.http("GET", "/api/tasks/" + fixture.task(p), "operator", null, null, false), 200);
  }

  JsonNode post(JsonNode p, String endpoint, String user, String body, int status)
      throws Exception {
    return h.data(
        h.http("POST", "/api/tasks/" + fixture.task(p) + "/" + endpoint, user, null, body, true),
        status);
  }

  String body(long version, String resolution) {
    var map = new HashMap<String, Object>();
    map.put("version", version);
    map.put("reason", "人工核实测试");
    if (resolution != null) map.put("resolution", resolution);
    return h.json.writeValueAsString(map);
  }

  @Test
  void cancellationIsScopedCsrfProtectedAndReplayableAcrossRestart() throws Exception {
    var p = fixture.plan();
    var current = task(p);
    long version = current.get("version").asLong();
    assertThat(current.get("allowedActions").toString()).contains("CANCEL");
    post(p, "cancel", "approver", body(version, null), 403);
    h.data(
        h.http(
            "POST",
            "/api/tasks/" + fixture.task(p) + "/cancel",
            "operator",
            null,
            body(version, null),
            false),
        403);
    post(p, "cancel", "operator", body(version - 1, null), 409);
    String minimal = "{\"version\":" + version + "}";
    var result = post(p, "cancel", "operator", minimal, 200);
    assertThat(result.get("outcome").asText()).isEqualTo("CANCELLED");
    h.backend.close();
    h.bootBackend("test");
    assertThat(post(p, "cancel", "operator", minimal, 200)).isEqualTo(result);
    assertThat(task(p).get("state").asText()).isEqualTo("FINISHED");
    assertThat(task(p).get("allowedActions").toString()).doesNotContain("CANCEL");
    UUID hidden = UUID.randomUUID();
    h.update(
        "INSERT INTO task(id,order_id,state,created_by,next_run_at) VALUES(?,'OTHER-T10','QUEUED','operator',NULL)",
        hidden);
    h.data(
        h.http(
            "POST", "/api/tasks/" + hidden + "/cancel", "operator", null, "{\"version\":1}", true),
        404);
    h.data(h.http("GET", "/api/tasks/" + hidden + "/handling", "operator", null, null, false), 404);
    assertThat(fixture.countOperations(p)).isEqualTo("0");
    assertThat(h.data(h.create(fixture.order(p), h.id()), 201).get("task").get("id").asText())
        .isNotEqualTo(fixture.task(p).toString());
  }

  @Test
  void noIntentTakeoverRequiresApproverAndExplicitResolution() throws Exception {
    var p = fixture.plan();
    long version = task(p).get("version").asLong();
    post(p, "takeover", "operator", body(version, null), 403);
    post(p, "takeover", "approver", body(version, null).replace("人工核实测试", "   "), 400);
    String forged = body(version, null);
    forged = forged.substring(0, forged.length() - 1) + ",\"actor\":\"operator\"}";
    post(p, "takeover", "approver", forged, 400);
    post(p, "takeover", "approver", body(version, null), 200);
    assertThat(task(p).get("state").asText()).isEqualTo("MANUAL");
    assertThat(h.data(h.create(fixture.order(p), h.id()), 200).get("task").get("id").asText())
        .isEqualTo(fixture.task(p).toString());
    String resolve = body(task(p).get("version").asLong(), "NO_EXECUTION");
    var done = post(p, "resolve", "approver", resolve, 200);
    assertThat(done.get("outcome").asText()).isEqualTo("MANUAL_CLOSED_WITHOUT_EXECUTION");
    assertThat(post(p, "resolve", "approver", resolve, 200)).isEqualTo(done);
    assertThat(
            h.data(
                    h.http(
                        "GET",
                        "/api/tasks/" + fixture.task(p) + "/handling",
                        "operator",
                        null,
                        null,
                        false),
                    200)
                .size())
        .isEqualTo(2);
    assertThat(h.data(h.create(fixture.order(p), h.id()), 201).get("task").get("id").asText())
        .isNotEqualTo(fixture.task(p).toString());
  }

  JsonNode unresolved() throws Exception {
    var p = fixture.plan();
    fixture.approve(p);
    UUID operation = UUID.randomUUID();
    h.update(
        "INSERT INTO execution(operation_id,task_id,plan_id,params_hash,status) VALUES(?,?,?,?,'UNKNOWN')",
        operation,
        fixture.task(p),
        UUID.fromString(p.get("id").asText()),
        p.get("paramsHash").asText());
    h.update(
        "UPDATE task SET state='UNKNOWN',next_run_at=NULL,version=version+1 WHERE id=?",
        fixture.task(p));
    return p;
  }

  @Test
  void unknownCannotBeCancelledOrBypassedAndRequiresServerVerifiedResult() throws Exception {
    restartApiWithExecutionCredential();
    var p = unresolved();
    long expected = task(p).get("version").asLong();
    assertThat(post(p, "cancel", "operator", body(expected, null), 409).get("code").asText())
        .isEqualTo("EXECUTION_ALREADY_STARTED");
    assertThat(task(p).get("state").asText()).isEqualTo("UNKNOWN");
    post(p, "takeover", "approver", body(expected, null), 200);
    long version = task(p).get("version").asLong();
    post(p, "resolve", "approver", body(version, "NO_EXECUTION"), 409);
    assertThat(
            post(p, "resolve", "approver", body(version, "CONFIRM_OPERATION"), 409)
                .get("code")
                .asText())
        .isEqualTo("RESULT_UNCONFIRMED");
    assertThat(h.data(h.create(fixture.order(p), h.id()), 200).get("task").get("id").asText())
        .isEqualTo(fixture.task(p).toString());
    assertThat(fixture.countOperations(p)).isEqualTo("0");
    String operation = fixture.execution(p).get("operationId").asText();
    // 测试模拟旧请求稍后到达下游；人工接口本身只允许查询，不会发起此 PUT。
    var response =
        h.client.send(
            HttpRequest.newBuilder(URI.create(h.simBase + "/internal/operations/" + operation))
                .header("Authorization", "Bearer " + T09ExecutionIT.EXECUTE)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(p.get("params").toString()))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    h.data(response, 200);
    // 手工结案使用只读网络调用，因此给 API 进程配置执行查询凭证，保留 Worker 关闭。
    restartApiWithExecutionCredential();
    String body = body(version, "CONFIRM_OPERATION");
    var done = post(p, "resolve", "approver", body, 200);
    assertThat(done.get("outcome").asText()).isEqualTo("MANUAL_REPAIR_VERIFIED");
    assertThat(done.get("operationId").asText()).isEqualTo(operation);
    assertThat(done.get("evidenceId").isNull()).isFalse();
    assertThat(post(p, "resolve", "approver", body, 200)).isEqualTo(done);
    assertThat(fixture.countOperations(p)).isEqualTo("1");
    assertThat(
            h.scalar(
                "agent_app", "SELECT count(*) FROM order_guard WHERE task_id=?", fixture.task(p)))
        .isEqualTo("0");
  }

  void restartApiWithExecutionCredential() throws Exception {
    h.backend.close();
    h.backend =
        h.process("backend")
            .withEnv("WORKER_ENABLED", "false")
            .withEnv("DEMO_OPERATOR_PASSWORD", T06TaskIT.PASSWORD)
            .withEnv("DEMO_APPROVER_PASSWORD", T06TaskIT.PASSWORD)
            .withEnv("SIMULATOR_BASE_URL", "http://simulator:8080")
            .withEnv("SIMULATOR_READ_TOKEN", T06TaskIT.READ)
            .withEnv("SIMULATOR_EXECUTE_TOKEN", T09ExecutionIT.EXECUTE);
    h.backend.start();
    h.base = T06TaskIT.url(h.backend);
    h.accountClients.clear();
    h.csrfTokens.clear();
  }
}
