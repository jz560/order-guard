package com.example.orderagent.simulator.business;

import static org.assertj.core.api.Assertions.*;

import com.example.orderagent.simulator.SimulatorApplication;
import com.example.orderagent.simulator.business.BusinessTypes.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BusinessSimulationIT {
  static final String READ = "ephemeral-test-read-token",
      WRITE = "ephemeral-test-write-token",
      ADMIN = "ephemeral-test-admin-token";
  final JsonMapper json = JsonMapper.builder().build();
  final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  PostgreSQLContainer<?> postgres;
  ConfigurableApplicationContext context;
  JdbcTemplate jdbc;
  String base;

  @BeforeAll
  void start() {
    postgres = new PostgreSQLContainer<>("postgres:17.6-bookworm");
    postgres.start();
    boot("test");
  }

  void boot(String profile) {
    context =
        new SpringApplicationBuilder(SimulatorApplication.class)
            .run(
                "--server.port=0",
                "--spring.profiles.active=" + profile,
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--simulator.auth.read-token=" + READ,
                "--simulator.auth.execute-token=" + WRITE,
                "--simulator.auth.admin-token=" + ADMIN);
    jdbc = context.getBean(JdbcTemplate.class);
    base = "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  void stop() {
    if (context != null) context.close();
    if (postgres != null) postgres.close();
  }

  HttpResponse<String> request(String method, String path, String token, String body)
      throws Exception {
    return request(method, path, token, body, Duration.ofSeconds(8));
  }

  HttpResponse<String> request(
      String method, String path, String token, String body, Duration timeout) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(timeout)
            .header("Content-Type", "application/json");
    if (token != null) builder.header("Authorization", "Bearer " + token);
    return client.send(
        builder
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  JsonNode data(HttpResponse<String> response, int status) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    return json.readTree(response.body());
  }

  JsonNode reset(Scenario scenario) throws Exception {
    var state =
        data(request("POST", "/internal/demo/scenarios/" + scenario + "/reset", ADMIN, null), 200);
    return data(
        request("GET", "/internal/orders/" + state.get("activeOrderId").asText(), READ, null), 200);
  }

  String repair(JsonNode order) {
    return json.writeValueAsString(
        new RepairRequest(
            order.get("id").asText(),
            order.get("paymentId").asText(),
            10000L,
            "CNY",
            order.get("version").asLong(),
            Action.VERIFY_PAYMENT_AND_ADVANCE_ORDER));
  }

  String id() {
    return UUID.randomUUID().toString();
  }

  void fault(Scenario scenario, Fault fault) throws Exception {
    data(
        request(
            "PUT",
            "/internal/demo/scenarios/" + scenario + "/fault",
            ADMIN,
            "{\"fault\":\"" + fault + "\"}"),
        200);
  }

  @Test
  void sixScenariosHaveDistinctEvidenceAndRepairOutcomes() throws Exception {
    assertThat(data(request("GET", "/internal/demo/scenarios", ADMIN, null), 200).size())
        .isEqualTo(6);
    for (var scenario : Scenario.values()) {
      var order = reset(scenario);
      String paymentPath = "/internal/payments/" + order.get("paymentId").asText();
      if (scenario == Scenario.MISSING_EVIDENCE) {
        data(request("GET", paymentPath, READ, null), 404);
        data(request("GET", paymentPath + "/callbacks", READ, null), 404);
      } else {
        var payment = data(request("GET", paymentPath, READ, null), 200);
        assertThat(payment.get("orderId").asText()).isEqualTo(order.get("id").asText());
        var callback = data(request("GET", paymentPath + "/callbacks", READ, null), 200).get(0);
        if (scenario == Scenario.NORMAL_DELAY) {
          assertThat(callback.get("receivedAt").isNull()).isTrue();
          assertThat(callback.get("result").asText()).isEqualTo("PENDING");
          assertThat(java.time.Instant.parse(payment.get("paidAt").asText()))
              .isAfter(java.time.Instant.now().minusSeconds(30));
        }
        if (scenario == Scenario.CALLBACK_FAILED)
          assertThat(callback.get("errorCode").asText()).isEqualTo("ORDER_UPDATE_FAILED");
        if (scenario == Scenario.AMOUNT_CONFLICT)
          assertThat(payment.get("amountMinor").asLong()).isEqualTo(9900);
        if (scenario == Scenario.ALREADY_REPAIRED)
          assertThat(callback.get("result").asText()).isEqualTo("SUCCEEDED");
      }
      boolean success = scenario == Scenario.CALLBACK_FAILED || scenario == Scenario.NORMAL_DELAY;
      var result =
          data(
              request("PUT", "/internal/operations/" + id(), WRITE, repair(order)),
              success ? 200 : 422);
      assertThat(result.get("status").asText()).isEqualTo(success ? "SUCCEEDED" : "REJECTED");
      String expected =
          switch (scenario) {
            case CALLBACK_FAILED, NORMAL_DELAY -> "VERIFIED_AND_ADVANCED";
            case MISSING_EVIDENCE -> "PAYMENT_EVIDENCE_MISSING";
            case AMOUNT_CONFLICT -> "AMOUNT_MISMATCH";
            default -> "ORDER_NOT_PENDING";
          };
      assertThat(result.get("code").asText()).isEqualTo(expected);
      var current =
          data(request("GET", "/internal/orders/" + order.get("id").asText(), READ, null), 200);
      assertThat(current.get("version").asLong())
          .isEqualTo(order.get("version").asLong() + (success ? 1 : 0));
    }
  }

  @Test
  void credentialsValidationAndUnknownResults() throws Exception {
    var order = reset(Scenario.CALLBACK_FAILED);
    String path = "/internal/operations/" + id(), body = repair(order);
    for (String token : Arrays.asList(null, "incorrect", READ, ADMIN)) {
      var response = request("PUT", path, token, body);
      assertThat(data(response, 403).get("code").asText()).isEqualTo("FORBIDDEN");
      assertThat(response.headers().firstValue("X-Correlation-ID")).isPresent();
    }
    data(request("GET", "/internal/orders/" + order.get("id").asText(), null, null), 403);
    data(request("POST", "/internal/demo/scenarios/CALLBACK_FAILED/reset", WRITE, null), 403);
    data(request("GET", path, READ, null), 404);
    for (String invalid :
        List.of(
            "{}",
            body.replace("10000", "-1"),
            body.replace("10000", "10000.5"),
            body.replace("10000", "null"),
            body.replace("CNY", "USD"),
            body.replace("VERIFY_PAYMENT_AND_ADVANCE_ORDER", "REFUND"),
            body.substring(0, body.length() - 1) + ",\"extra\":1}"))
      assertThat(data(request("PUT", path, WRITE, invalid), 400).get("code").asText())
          .isEqualTo("INVALID_REQUEST");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM operations WHERE operation_id=?",
                Integer.class,
                path.substring(path.lastIndexOf('/') + 1)))
        .isZero();
  }

  @Test
  void sameIdConcurrentReplayAndAllFixedParameterConflicts() throws Exception {
    var order = reset(Scenario.CALLBACK_FAILED);
    String path = "/internal/operations/" + id(), body = repair(order);
    try (var pool = Executors.newFixedThreadPool(8)) {
      var gate = new CountDownLatch(1);
      List<Future<HttpResponse<String>>> futures = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        futures.add(
            pool.submit(
                () -> {
                  gate.await();
                  return request("PUT", path, WRITE, body);
                }));
      gate.countDown();
      String original = null;
      for (var future : futures) {
        var response = future.get(15, TimeUnit.SECONDS);
        data(response, 200);
        if (original == null) original = response.body();
        else assertThat(response.body()).isEqualTo(original);
      }
    }
    for (String changed :
        List.of(
            body.replace("10000", "10001"),
            body.replace("\"expectedVersion\":1", "\"expectedVersion\":2"),
            body.replace(order.get("id").asText(), "other-order"),
            body.replace(order.get("paymentId").asText(), "other-payment")))
      assertThat(data(request("PUT", path, WRITE, changed), 409).get("code").asText())
          .isEqualTo("IDEMPOTENCY_CONFLICT");
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM orders WHERE id=?", Long.class, order.get("id").asText()))
        .isEqualTo(2);
  }

  @Test
  void differentIdsCompeteAndResetPreservesHistory() throws Exception {
    var order = reset(Scenario.CALLBACK_FAILED);
    String body = repair(order), first = id(), second = id();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a = pool.submit(() -> request("PUT", "/internal/operations/" + first, WRITE, body));
      var b = pool.submit(() -> request("PUT", "/internal/operations/" + second, WRITE, body));
      assertThat(List.of(a.get().statusCode(), b.get().statusCode()))
          .containsExactlyInAnyOrder(200, 422);
    }
    var newOrder = reset(Scenario.CALLBACK_FAILED);
    assertThat(newOrder.get("id").asText()).isNotEqualTo(order.get("id").asText());
    for (String operationId : List.of(first, second)) {
      var saved = request("GET", "/internal/operations/" + operationId, READ, null);
      data(saved, 200);
      assertThat(request("PUT", "/internal/operations/" + operationId, WRITE, body).body())
          .isEqualTo(saved.body());
    }
    assertThat(
            data(request("PUT", "/internal/operations/" + id(), WRITE, body), 422)
                .get("code")
                .asText())
        .isEqualTo("SCENARIO_REPLACED");
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM orders WHERE id=?", Long.class, newOrder.get("id").asText()))
        .isEqualTo(1);
  }

  @Test
  void databaseFailureRollsBackBusinessAndOperationTogether() throws Exception {
    var order = reset(Scenario.CALLBACK_FAILED);
    String operationId = id();
    jdbc.execute(
        "ALTER TABLE operations ADD CONSTRAINT t05_test_reject_success CHECK(status <> 'SUCCEEDED') NOT VALID");
    try {
      data(request("PUT", "/internal/operations/" + operationId, WRITE, repair(order)), 500);
      assertThat(
              jdbc.queryForObject(
                  "SELECT version FROM orders WHERE id=?", Long.class, order.get("id").asText()))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM operations WHERE operation_id=?",
                  Integer.class,
                  operationId))
          .isZero();
    } finally {
      jdbc.execute("ALTER TABLE operations DROP CONSTRAINT t05_test_reject_success");
    }
    data(request("PUT", "/internal/operations/" + operationId, WRITE, repair(order)), 200);
  }

  @Test
  void delayedCommittedResponseCanBeConfirmedAndQueryFaultCanBeCleared() throws Exception {
    var order = reset(Scenario.CALLBACK_FAILED);
    String path = "/internal/operations/" + id(), body = repair(order);
    fault(Scenario.CALLBACK_FAILED, Fault.DELAY_RESPONSE_ONCE);
    assertThatThrownBy(() -> request("PUT", path, WRITE, body, Duration.ofMillis(600)))
        .isInstanceOf(HttpTimeoutException.class);
    assertThat(data(request("GET", path, READ, null), 200).get("status").asText())
        .isEqualTo("SUCCEEDED");
    data(request("PUT", path, WRITE, body), 200);
    fault(Scenario.CALLBACK_FAILED, Fault.QUERY_UNAVAILABLE);
    assertThat(data(request("GET", path, READ, null), 503).get("code").asText())
        .isEqualTo("RESULT_UNAVAILABLE");
    fault(Scenario.CALLBACK_FAILED, Fault.NONE);
    data(request("GET", path, READ, null), 200);
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM orders WHERE id=?", Long.class, order.get("id").asText()))
        .isEqualTo(2);
  }

  @Test
  void executionRechecksChangedFactsAndPersistsRejection() throws Exception {
    for (String change : List.of("version", "closed", "payment", "link")) {
      var order = reset(Scenario.CALLBACK_FAILED);
      String orderId = order.get("id").asText(), paymentId = order.get("paymentId").asText();
      String expected;
      switch (change) {
        case "version" -> {
          jdbc.update("UPDATE orders SET version=version+1 WHERE id=?", orderId);
          expected = "ORDER_VERSION_CHANGED";
        }
        case "closed" -> {
          jdbc.update("UPDATE orders SET status='CLOSED' WHERE id=?", orderId);
          expected = "ORDER_NOT_PENDING";
        }
        case "payment" -> {
          jdbc.update("UPDATE payments SET status='PENDING' WHERE id=?", paymentId);
          expected = "PAYMENT_NOT_SUCCESSFUL";
        }
        default -> {
          var other = reset(Scenario.NORMAL_DELAY);
          jdbc.update(
              "UPDATE payments SET order_id=? WHERE id=?", other.get("id").asText(), paymentId);
          expected = "PAYMENT_LINK_MISMATCH";
        }
      }
      String path = "/internal/operations/" + id();
      var rejected = data(request("PUT", path, WRITE, repair(order)), 422);
      assertThat(rejected.get("code").asText()).isEqualTo(expected);
      assertThat(data(request("GET", path, READ, null), 200)).isEqualTo(rejected);
      assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, orderId))
          .isNotEqualTo("PAID");
    }
  }

  @Test
  void resetRacingExecutionNeverMutatesNewGeneration() throws Exception {
    var order = reset(Scenario.CALLBACK_FAILED);
    String path = "/internal/operations/" + id();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      var write =
          pool.submit(
              () -> {
                gate.await();
                return request("PUT", path, WRITE, repair(order));
              });
      var reset =
          pool.submit(
              () -> {
                gate.await();
                return request(
                    "POST", "/internal/demo/scenarios/CALLBACK_FAILED/reset", ADMIN, null);
              });
      gate.countDown();
      var response = write.get(15, TimeUnit.SECONDS);
      assertThat(response.statusCode()).isIn(200, 422);
      var next = data(reset.get(15, TimeUnit.SECONDS), 200).get("activeOrderId").asText();
      assertThat(next).isNotEqualTo(order.get("id").asText());
      assertThat(jdbc.queryForObject("SELECT version FROM orders WHERE id=?", Long.class, next))
          .isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, next))
          .isEqualTo("PENDING_PAYMENT");
      assertThat(request("GET", path, READ, null).body()).isEqualTo(response.body());
    }
  }

  @Test
  void restartPreservesDataAndProductionDisablesDemoAndPersistedFaults() throws Exception {
    var order = reset(Scenario.CALLBACK_FAILED);
    String path = "/internal/operations/" + id();
    data(request("PUT", path, WRITE, repair(order)), 200);
    fault(Scenario.CALLBACK_FAILED, Fault.QUERY_UNAVAILABLE);
    context.close();
    try {
      boot("production");
      data(request("GET", "/internal/demo/scenarios", ADMIN, null), 403);
      data(request("POST", "/internal/demo/scenarios/CALLBACK_FAILED/reset", ADMIN, null), 403);
      data(
          request(
              "PUT",
              "/internal/demo/scenarios/CALLBACK_FAILED/fault",
              ADMIN,
              "{\"fault\":\"NONE\"}"),
          403);
      data(request("GET", path, READ, null), 200);
      assertThat(
              jdbc.queryForObject(
                  "SELECT active_order_id FROM simulation_scenarios WHERE scenario='CALLBACK_FAILED'",
                  String.class))
          .isEqualTo(order.get("id").asText());
    } finally {
      context.close();
      boot("test");
    }
    assertThat(data(request("GET", "/internal/demo/scenarios", ADMIN, null), 200).toString())
        .contains(order.get("id").asText());
    fault(Scenario.CALLBACK_FAILED, Fault.NONE);
    data(request("GET", path, READ, null), 200);
  }
}
