package com.example.orderagent.simulator.recovery;

import static org.assertj.core.api.Assertions.*;

import com.example.orderagent.simulator.recovery.ProbeProtocol.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.*;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/** 独立 Linux JVM + 真实 PostgreSQL + HTTP；SIGKILL 不是内存模拟或优雅退出。 */
class T04RecoveryIT {
  private static final String IMAGE =
      System.getenv().getOrDefault("T04_JAVA_IMAGE", "eclipse-temurin:21.0.8_9-jdk-jammy");
  private static final String READ = UUID.randomUUID().toString(),
      WRITE = UUID.randomUUID().toString();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static Network network;
  private static PostgreSQLContainer<?> postgres;
  private static Path evidence;

  @BeforeAll
  static void infrastructure() throws Exception {
    evidence = Path.of("target", "t04-evidence", UUID.randomUUID().toString());
    Files.createDirectories(evidence);
    Files.writeString(Path.of("target", "t04-latest.txt"), evidence.toAbsolutePath().toString());
    network = Network.newNetwork();
    postgres =
        new PostgreSQLContainer<>("postgres:17.6-bookworm")
            .withNetwork(network)
            .withNetworkAliases("pg")
            .withDatabaseName("postgres")
            .withUsername("postgres")
            .withPassword("ephemeral-t04-admin")
            .withEnv("BACKEND_DB_PASSWORD", "ephemeral-t04-backend")
            .withEnv("SIMULATOR_DB_PASSWORD", "ephemeral-t04-simulator")
            .withCopyFileToContainer(
                MountableFile.forHostPath(
                    Path.of("../infra/postgres/init-databases.sh").toAbsolutePath(), 0755),
                "/docker-entrypoint-initdb.d/10-databases.sh");
    postgres.start();
  }

  @AfterAll
  static void cleanup() {
    if (postgres != null) postgres.close();
    if (network != null) network.close();
  }

  private static GenericContainer<?> javaProcess(String module, String main) {
    var jar =
        Path.of(
                module.equals("backend")
                    ? "../backend/target/backend-0.1.0.jar"
                    : "target/simulator-0.1.0.jar")
            .toAbsolutePath();
    assertThat(jar).isRegularFile();
    return new GenericContainer<>(IMAGE)
        .withNetwork(network)
        .withEnv("T04_MODE", "test")
        .withEnv(
            "T04_DB_URL",
            "jdbc:postgresql://pg:5432/"
                + (module.equals("backend") ? "agent_app" : "agent_simulator"))
        .withEnv("T04_DB_USER", module.equals("backend") ? "backend_app" : "simulator_app")
        .withEnv(
            "T04_DB_PASSWORD",
            module.equals("backend") ? "ephemeral-t04-backend" : "ephemeral-t04-simulator")
        .withCopyFileToContainer(MountableFile.forHostPath(jar), "/tmp/t04-probe.jar")
        .withCreateContainerCmdModifier(command -> command.withEntrypoint("java"))
        .withCommand(
            "-Dloader.main=" + main,
            "-cp",
            "/tmp/t04-probe.jar",
            "org.springframework.boot.loader.launch.PropertiesLauncher");
  }

  private static final class Harness implements AutoCloseable {
    final String order = UUID.randomUUID().toString();
    final GenericContainer<?> downstream;

    Harness(boolean drop) {
      downstream =
          javaProcess("simulator", "com.example.orderagent.simulator.recovery.T04Downstream")
              .withNetworkAliases("downstream")
              .withExposedPorts(8094)
              .withEnv("T04_READ_TOKEN", READ)
              .withEnv("T04_WRITE_TOKEN", WRITE)
              .withEnv("T04_SEED_ORDER_ID", order)
              .withEnv("T04_FAULT", drop ? "DROP_RESPONSE" : "NONE")
              .waitingFor(
                  Wait.forLogMessage(".*T04_READY.*\\n", 1)
                      .withStartupTimeout(Duration.ofSeconds(60)));
      downstream.start();
    }

    Request request() {
      return new Request(
          order, "T04-PAY-" + order, 10000, "CNY", 1, "VERIFY_PAYMENT_AND_ADVANCE_ORDER");
    }

    HttpResponse<String> http(String method, String path, Object body, String token)
        throws Exception {
      var builder =
          HttpRequest.newBuilder(
                  URI.create(
                      "http://"
                          + downstream.getHost()
                          + ":"
                          + downstream.getMappedPort(8094)
                          + path))
              .timeout(Duration.ofSeconds(10))
              .header("Authorization", "Bearer " + token);
      if (body == null) builder.GET();
      else
        builder
            .header("Content-Type", "application/json")
            .method(
                method,
                HttpRequest.BodyPublishers.ofString(
                    body instanceof String text
                        ? text
                        : ProbeProtocol.JSON.writeValueAsString(body)));
      return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    tools.jackson.databind.JsonNode stats() throws Exception {
      return ProbeProtocol.JSON.readTree(http("GET", "/t04/stats", null, READ).body());
    }

    GenericContainer<?> caller(String id, String command, boolean pause) {
      var process =
          javaProcess("backend", "com.example.orderagent.backend.recovery.T04Caller")
              .withEnv("T04_OPERATION_ID", id)
              .withEnv("T04_ORDER_ID", order)
              .withEnv("T04_DOWNSTREAM_URL", "http://downstream:8094")
              .withEnv("T04_READ_TOKEN", READ);
      if (command.equals("send")) process.withEnv("T04_WRITE_TOKEN", WRITE);
      if (pause)
        process
            .withEnv("T04_PAUSE", "AFTER_RESPONSE_BEFORE_SAVE")
            .waitingFor(
                Wait.forLogMessage(".*T04_CHECKPOINT AFTER_RESPONSE_BEFORE_SAVE.*\\n", 1)
                    .withStartupTimeout(Duration.ofSeconds(60)));
      else
        process.withStartupCheckStrategy(
            new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(60)));
      process.withCommand(
          "-Dloader.main=com.example.orderagent.backend.recovery.T04Caller",
          "-cp",
          "/tmp/t04-probe.jar",
          "org.springframework.boot.loader.launch.PropertiesLauncher",
          command);
      process.start();
      return process;
    }

    @Override
    public void close() {
      downstream.close();
    }
  }

  private static Connection db(String name) throws SQLException {
    return DriverManager.getConnection(
        "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + name,
        "postgres",
        "ephemeral-t04-admin");
  }

  private static String scalar(String database, String sql, String... values) throws SQLException {
    try (var connection = db(database);
        var statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < values.length; i++) statement.setString(i + 1, values[i]);
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getString(1);
      }
    }
  }

  private static String state(String id) throws SQLException {
    return scalar("agent_app", "SELECT status FROM t04_probe.intent WHERE operation_id=?", id);
  }

  private static void oneEffect(Harness harness) throws Exception {
    var response = harness.http("GET", "/t04/orders/" + harness.order, null, READ);
    assertThat(response.statusCode()).isEqualTo(200);
    var order = ProbeProtocol.JSON.readValue(response.body(), ProbeProtocol.Order.class);
    assertThat(order.status()).isEqualTo("PAID");
    assertThat(order.version()).isEqualTo(2);
    assertThat(order.effectCount()).isEqualTo(1);
  }

  private static void save(String scenario, Map<String, Object> result) throws Exception {
    Map<String, Object> record = new LinkedHashMap<>(result);
    record.put("scenario", scenario);
    record.put("javaImage", IMAGE);
    record.put("verifiedAt", java.time.Instant.now().toString());
    Files.writeString(
        evidence.resolve(scenario + ".json"),
        ProbeProtocol.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(record));
  }

  private static String processResult(GenericContainer<?> process) {
    return process
        .getLogs()
        .lines()
        .filter(line -> line.startsWith("T04_"))
        .reduce("", (a, b) -> a + b + "\n");
  }

  @Test
  void concurrentReplayAndParameterConflictAreControlled() throws Exception {
    try (var h = new Harness(false)) {
      String id = UUID.randomUUID().toString();
      String path = "/t04/operations/" + id;
      assertThat(h.http("PUT", path, h.request(), READ).statusCode()).isEqualTo(403);
      var forbidden = ProbeProtocol.JSON.readTree(h.http("PUT", path, h.request(), READ).body());
      assertThat(forbidden.path("code").asText()).isEqualTo("FORBIDDEN");
      assertThat(forbidden.path("message").asText()).isNotBlank();
      assertThat(forbidden.path("correlationId").asText()).isNotBlank();
      assertThat(h.http("GET", path, null, "wrong-token").statusCode()).isEqualTo(403);
      assertThat(h.http("PUT", path, "null", WRITE).statusCode()).isEqualTo(400);
      assertThat(h.http("PUT", path, "{\"unknown\":true}", WRITE).statusCode()).isEqualTo(400);
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++)
          futures.add(
              executor.submit(
                  () -> {
                    start.await();
                    return h.http("PUT", path, h.request(), WRITE);
                  }));
        start.countDown();
        for (var future : futures)
          assertThat(future.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
      }
      var original = h.http("GET", path, null, READ).body();
      assertThat(h.http("PUT", path, h.request(), WRITE).body()).isEqualTo(original);
      var changedAmount =
          new Request(
              h.order, "T04-PAY-" + h.order, 9999, "CNY", 1, "VERIFY_PAYMENT_AND_ADVANCE_ORDER");
      assertThat(h.http("PUT", path, changedAmount, WRITE).statusCode()).isEqualTo(409);
      var changedVersion =
          new Request(
              h.order, "T04-PAY-" + h.order, 10000, "CNY", 2, "VERIFY_PAYMENT_AND_ADVANCE_ORDER");
      assertThat(h.http("PUT", path, changedVersion, WRITE).statusCode()).isEqualTo(409);
      assertThat(h.http("GET", path, null, READ).body()).isEqualTo(original);
      oneEffect(h);
      assertThat(
              scalar(
                  "agent_simulator",
                  "SELECT count(*) FROM t04_probe.operations WHERE operation_id=?",
                  id))
          .isEqualTo("1");
      save(
          "concurrent-replay",
          Map.of(
              "operationId",
              id,
              "concurrentRequests",
              8,
              "effectCount",
              1,
              "orderVersion",
              2,
              "parameterConflictHttp",
              409,
              "readCredentialCannotWrite",
              true));
    }
  }

  @Test
  void differentOperationIdsCannotAdvanceOneOrderTwice() throws Exception {
    try (var h = new Harness(false);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () ->
                  h.http("PUT", "/t04/operations/" + UUID.randomUUID(), h.request(), WRITE)
                      .statusCode());
      var second =
          executor.submit(
              () ->
                  h.http("PUT", "/t04/operations/" + UUID.randomUUID(), h.request(), WRITE)
                      .statusCode());
      assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(200, 422);
      oneEffect(h);
      save(
          "competing-operation-ids",
          Map.of("orderId", h.order, "statuses", List.of(200, 422), "effectCount", 1));
    }
  }

  @Test
  void committedButLostResponseRecoversByQueryOnly() throws Exception {
    try (var h = new Harness(true)) {
      String id = UUID.randomUUID().toString();
      String sendLog;
      try (var caller = h.caller(id, "send", false)) {
        sendLog = processResult(caller);
        assertThat(sendLog).contains("UNKNOWN", "TRANSPORT_RESULT_UNKNOWN");
      }
      assertThat(state(id)).isEqualTo("UNKNOWN");
      oneEffect(h);
      int writes = h.stats().path("writeRequests").asInt();
      assertThat(writes).isGreaterThanOrEqualTo(1);
      String recoveryLog;
      try (var recovered = h.caller(id, "recover", false)) {
        recoveryLog = processResult(recovered);
        assertThat(recoveryLog).contains("SUCCEEDED", "VERIFIED");
      }
      assertThat(state(id)).isEqualTo("SUCCEEDED");
      oneEffect(h);
      assertThat(h.stats().path("writeRequests").asInt()).isEqualTo(writes);
      assertThat(h.stats().path("operationQueries").asInt()).isGreaterThanOrEqualTo(1);
      save(
          "lost-response",
          Map.of(
              "operationId",
              id,
              "beforeRecovery",
              "UNKNOWN",
              "afterRecovery",
              state(id),
              "writesBeforeRecovery",
              writes,
              "writesAfterRecovery",
              h.stats().path("writeRequests").asInt(),
              "effectCount",
              1,
              "sendLog",
              sendLog,
              "recoveryLog",
              recoveryLog));
    }
  }

  @Test
  void sigkillAfterResponseBeforeLocalSaveRecoversSameOperation() throws Exception {
    try (var h = new Harness(false)) {
      String id = UUID.randomUUID().toString();
      long exit;
      try (var caller = h.caller(id, "send", true)) {
        assertThat(state(id)).isEqualTo("IN_FLIGHT");
        oneEffect(h);
        assertThat(
                scalar(
                    "agent_simulator",
                    "SELECT status FROM t04_probe.operations WHERE operation_id=?",
                    id))
            .isEqualTo("SUCCEEDED");
        var docker = DockerClientFactory.instance().client();
        docker.killContainerCmd(caller.getContainerId()).withSignal("KILL").exec();
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (Boolean.TRUE.equals(
                docker.inspectContainerCmd(caller.getContainerId()).exec().getState().getRunning())
            && System.nanoTime() < deadline) Thread.sleep(50);
        exit =
            docker.inspectContainerCmd(caller.getContainerId()).exec().getState().getExitCodeLong();
        assertThat(exit).isEqualTo(137);
      }
      assertThat(state(id)).isEqualTo("IN_FLIGHT");
      int writes = h.stats().path("writeRequests").asInt();
      String recoveryLog;
      try (var recovered = h.caller(id, "recover", false)) {
        recoveryLog = processResult(recovered);
        assertThat(recoveryLog).contains("SUCCEEDED", "VERIFIED");
      }
      assertThat(state(id)).isEqualTo("SUCCEEDED");
      oneEffect(h);
      assertThat(h.stats().path("writeRequests").asInt()).isEqualTo(writes);
      save(
          "sigkill-recovery",
          Map.of(
              "operationId",
              id,
              "signal",
              "SIGKILL",
              "exitCode",
              exit,
              "checkpoint",
              "AFTER_RESPONSE_BEFORE_SAVE",
              "beforeRecovery",
              "IN_FLIGHT",
              "afterRecovery",
              state(id),
              "effectCount",
              1,
              "writeRequests",
              writes,
              "recoveryLog",
              recoveryLog));
    }
  }

  @Test
  void notFoundRemainsUnknownAndRetainsOrderReservation() throws Exception {
    try (var h = new Harness(false)) {
      String id = UUID.randomUUID().toString();
      try (var prepared = h.caller(id, "prepare", false)) {
        assertThat(processResult(prepared)).contains("PREPARED");
      }
      try (var recovered = h.caller(id, "recover", false)) {
        assertThat(processResult(recovered)).contains("UNKNOWN", "OPERATION_NOT_FOUND_UNKNOWN");
      }
      assertThat(state(id)).isEqualTo("UNKNOWN");
      assertThat(h.stats().path("writeRequests").asInt()).isZero();
      assertThat(
              scalar(
                  "agent_simulator",
                  "SELECT effect_count FROM t04_probe.orders WHERE order_id=?",
                  h.order))
          .isEqualTo("0");
      assertThatThrownBy(
              () -> {
                try (var connection = db("agent_app");
                    var insert =
                        connection.prepareStatement(
                            """
            INSERT INTO t04_probe.intent SELECT ?,order_id,payment_id,amount_minor,currency,expected_version,action,'PREPARED','NONE',created_at,updated_at
            FROM t04_probe.intent WHERE operation_id=?
            """)) {
                  insert.setString(1, UUID.randomUUID().toString());
                  insert.setString(2, id);
                  insert.executeUpdate();
                }
              })
          .isInstanceOf(SQLException.class)
          .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23505"));
      save(
          "not-found-unknown",
          Map.of(
              "operationId",
              id,
              "status",
              state(id),
              "writeRequests",
              0,
              "effectCount",
              0,
              "orderReservationRetained",
              true));
    }
  }

  @Test
  void successfulOperationStillRequiresBusinessVerification() throws Exception {
    try (var h = new Harness(true)) {
      String id = UUID.randomUUID().toString();
      try (var caller = h.caller(id, "send", false)) {
        assertThat(processResult(caller)).contains("UNKNOWN");
      }
      oneEffect(h);
      // 仅测试夹具改变业务版本，模拟操作完成后又发生变化；恢复方必须核验当前事实。
      try (var connection = db("agent_simulator");
          var statement =
              connection.prepareStatement(
                  "UPDATE t04_probe.orders SET version=version+1 WHERE order_id=?")) {
        statement.setString(1, h.order);
        assertThat(statement.executeUpdate()).isEqualTo(1);
      }
      int writes = h.stats().path("writeRequests").asInt();
      try (var recovered = h.caller(id, "recover", false)) {
        assertThat(processResult(recovered)).contains("UNKNOWN", "BUSINESS_VERIFICATION_FAILED");
      }
      assertThat(state(id)).isEqualTo("UNKNOWN");
      assertThat(h.stats().path("writeRequests").asInt()).isEqualTo(writes);
      assertThat(
              scalar(
                  "agent_simulator",
                  "SELECT status FROM t04_probe.operations WHERE operation_id=?",
                  id))
          .isEqualTo("SUCCEEDED");
      save(
          "business-verification-mismatch",
          Map.of(
              "operationId",
              id,
              "operationStatus",
              "SUCCEEDED",
              "callerStatus",
              state(id),
              "code",
              "BUSINESS_VERIFICATION_FAILED",
              "newWriteRequests",
              0));
    }
  }

  @Test
  void resultPersistenceFailureRollsBackBusinessUpdate() throws Exception {
    try (var h = new Harness(false)) {
      String id = UUID.randomUUID().toString();
      // 测试夹具生成的 UUID，不含外部输入；约束只让此操作的结果落库失败。
      try (var connection = db("agent_simulator");
          var statement = connection.createStatement()) {
        statement.execute(
            "ALTER TABLE t04_probe.operations ADD CONSTRAINT t04_atomic_probe CHECK (status <> 'SUCCEEDED' OR operation_id <> '"
                + id
                + "')");
      }
      assertThat(h.http("PUT", "/t04/operations/" + id, h.request(), WRITE).statusCode())
          .isEqualTo(500);
      assertThat(
              scalar(
                  "agent_simulator",
                  "SELECT effect_count FROM t04_probe.orders WHERE order_id=?",
                  h.order))
          .isEqualTo("0");
      assertThat(
              scalar(
                  "agent_simulator",
                  "SELECT status FROM t04_probe.orders WHERE order_id=?",
                  h.order))
          .isEqualTo("PENDING_PAYMENT");
      assertThat(
              scalar(
                  "agent_simulator",
                  "SELECT count(*) FROM t04_probe.operations WHERE operation_id=?",
                  id))
          .isEqualTo("0");
      save(
          "atomic-rollback",
          Map.of(
              "operationId",
              id,
              "httpStatus",
              500,
              "effectCount",
              0,
              "orderStatus",
              "PENDING_PAYMENT",
              "operationRows",
              0));
    }
  }
}
