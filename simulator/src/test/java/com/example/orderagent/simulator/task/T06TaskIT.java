package com.example.orderagent.simulator.task;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 本轮打包的两个独立 Java 服务，通过 HTTP 通信，分别使用自己的 PostgreSQL 账号。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class T06TaskIT {
  static final String IMAGE =
      System.getenv().getOrDefault("T04_JAVA_IMAGE", "eclipse-temurin:21.0.8_9-jdk-jammy");
  static final String PASSWORD = "ephemeral-t06-account",
      READ = UUID.randomUUID().toString(),
      ADMIN = UUID.randomUUID().toString();
  final JsonMapper json = JsonMapper.builder().build();
  final Map<String, HttpClient> accountClients = new ConcurrentHashMap<>();
  final Map<String, String> csrfTokens = new ConcurrentHashMap<>();
  final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
          .build();
  Network network;
  PostgreSQLContainer<?> postgres;
  GenericContainer<?> simulator, backend;
  String base, simBase;

  @BeforeAll
  void start() throws Exception {
    network = Network.newNetwork();
    postgres =
        new PostgreSQLContainer<>("postgres:17.6-bookworm")
            .withNetwork(network)
            .withNetworkAliases("pg")
            .withDatabaseName("postgres")
            .withUsername("postgres")
            .withPassword("ephemeral-t06-admin")
            .withEnv("BACKEND_DB_PASSWORD", "ephemeral-t06-backend")
            .withEnv("SIMULATOR_DB_PASSWORD", "ephemeral-t06-simulator")
            .withCopyFileToContainer(
                MountableFile.forHostPath(
                    Path.of("../infra/postgres/init-databases.sh").toAbsolutePath(), 0755),
                "/docker-entrypoint-initdb.d/10-databases.sh");
    postgres.start();
    simulator =
        process("simulator")
            .withNetworkAliases("simulator")
            .withEnv("SIMULATOR_READ_TOKEN", READ)
            .withEnv("SIMULATOR_ADMIN_TOKEN", ADMIN);
    simulator.start();
    simBase = url(simulator);
    bootBackend("test");
  }

  GenericContainer<?> process(String module) {
    boolean app = module.equals("backend");
    var jar =
        Path.of(app ? "../backend/target/backend-0.1.0.jar" : "target/simulator-0.1.0.jar")
            .toAbsolutePath();
    assertThat(jar).isRegularFile();
    String prefix = app ? "BACKEND" : "SIMULATOR";
    return new GenericContainer<>(IMAGE)
        .withNetwork(network)
        .withExposedPorts(8080)
        .withCreateContainerCmdModifier(command -> command.withEntrypoint("java"))
        .withCopyFileToContainer(MountableFile.forHostPath(jar), "/tmp/app.jar")
        .withCommand("-jar", "/tmp/app.jar", "--server.port=8080")
        .withEnv("SPRING_PROFILES_ACTIVE", "test")
        .withEnv(
            prefix + "_DB_URL",
            "jdbc:postgresql://pg:5432/" + (app ? "agent_app" : "agent_simulator"))
        .withEnv(prefix + "_DB_USER", app ? "backend_app" : "simulator_app")
        .withEnv(prefix + "_DB_PASSWORD", app ? "ephemeral-t06-backend" : "ephemeral-t06-simulator")
        .waitingFor(
            Wait.forHttp("/actuator/health")
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofSeconds(90)));
  }

  void bootBackend(String profile) throws Exception {
    backend =
        process("backend")
            .withEnv("SPRING_PROFILES_ACTIVE", profile)
            .withEnv("MODEL_MODE", "stub")
            .withEnv("DEMO_OPERATOR_PASSWORD", PASSWORD)
            .withEnv("DEMO_APPROVER_PASSWORD", PASSWORD)
            .withEnv("SIMULATOR_BASE_URL", "http://simulator:8080")
            .withEnv("SIMULATOR_READ_TOKEN", READ);
    backend.start();
    base = url(backend);
    accountClients.clear();
    csrfTokens.clear();
    if (profile.equals("test"))
      csrfTokens.put(
          "operator",
          data(http("GET", "/api/auth/csrf", "operator", null, null, false), 200)
              .get("token")
              .asText());
  }

  static String url(GenericContainer<?> container) {
    return "http://" + container.getHost() + ":" + container.getMappedPort(8080);
  }

  @AfterAll
  void stop() {
    if (backend != null) backend.close();
    if (simulator != null) simulator.close();
    if (postgres != null) postgres.close();
    if (network != null) network.close();
  }

  HttpResponse<String> http(
      String method, String path, String user, String key, String body, boolean withCsrf)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json");
    if (user != null)
      builder.header(
          "Authorization",
          "Basic "
              + Base64.getEncoder()
                  .encodeToString((user + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)));
    if (key != null) builder.header("Idempotency-Key", key);
    if (withCsrf) {
      if (!csrfTokens.containsKey(user))
        csrfTokens.put(
            user,
            data(http("GET", "/api/auth/csrf", user, null, null, false), 200)
                .get("token")
                .asText());
      builder.header("X-CSRF-TOKEN", csrfTokens.get(user));
    }
    // 各账号使用独立浏览器会话，避免切换身份触发 CSRF 令牌失效。
    var accountClient =
        accountClients.computeIfAbsent(
            user == null ? "anonymous" : user,
            unused ->
                HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                    .build());
    return accountClient.send(
        builder
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  JsonNode data(HttpResponse<String> response, int expected) {
    assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
    return json.readTree(response.body());
  }

  String id() {
    return UUID.randomUUID().toString();
  }

  String order() throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(
                    URI.create(simBase + "/internal/demo/scenarios/CALLBACK_FAILED/reset"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + ADMIN)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return data(response, 200).get("activeOrderId").asText();
  }

  HttpResponse<String> create(String order, String key) throws Exception {
    return http("POST", "/api/tasks", "operator", key, "{\"orderId\":\"" + order + "\"}", true);
  }

  Connection db(String database) throws SQLException {
    return DriverManager.getConnection(
        "jdbc:postgresql://"
            + postgres.getHost()
            + ":"
            + postgres.getMappedPort(5432)
            + "/"
            + database,
        "postgres",
        "ephemeral-t06-admin");
  }

  String scalar(String database, String sql, Object... params) throws SQLException {
    try (var connection = db(database);
        var statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) statement.setObject(i + 1, params[i]);
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getString(1);
      }
    }
  }

  void update(String sql, Object... params) throws SQLException {
    try (var connection = db("agent_app");
        var statement = connection.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) statement.setObject(i + 1, params[i]);
      statement.executeUpdate();
    }
  }

  @Test
  void createsQueuedTaskWithEvidenceAndAuthenticatedAudit() throws Exception {
    String order = order(), key = id();
    var response = create(order, key);
    var created = data(response, 201);
    String taskId = created.get("task").get("id").asText();
    assertThat(response.headers().firstValue("Location")).contains("/api/tasks/" + taskId);
    assertThat(created.get("task").get("createdBy").asText()).isEqualTo("operator");
    assertThat(created.get("task").get("state").asText()).isEqualTo("QUEUED");
    assertThat(created.get("task").get("outcome").isNull()).isTrue();
    var detail = data(http("GET", "/api/tasks/" + taskId, "approver", null, null, false), 200);
    assertThat(detail).isEqualTo(created.get("task"));
    var evidence =
        data(http("GET", "/api/tasks/" + taskId + "/evidence", "operator", null, null, false), 200);
    assertThat(evidence.size()).isEqualTo(1);
    assertThat(evidence.get(0).get("payload").get("id").asText()).isEqualTo(order);
    assertThat(evidence.get(0).get("businessVersion").asInt()).isEqualTo(1);
    var audit =
        data(http("GET", "/api/tasks/" + taskId + "/audit", "approver", null, null, false), 200);
    assertThat(audit.size()).isEqualTo(1);
    assertThat(audit.get(0).get("actor").asText()).isEqualTo("operator");
    assertThat(audit.get(0).get("eventType").asText()).isEqualTo("TASK_CREATED");
    assertThat(response.headers().firstValue("X-Correlation-ID"))
        .contains(audit.get(0).get("correlationId").asText());
    assertThat(scalar("agent_simulator", "SELECT count(*) FROM operations")).isEqualTo("0");
    assertThat(scalar("agent_simulator", "SELECT status FROM orders WHERE id=?", order))
        .isEqualTo("PENDING_PAYMENT");
    assertThat(data(create(order, key), 200).get("task")).isEqualTo(created.get("task"));
  }

  @Test
  void concurrentDifferentKeysReturnOneTaskAndOneSnapshot() throws Exception {
    String order = order();
    try (var pool = Executors.newFixedThreadPool(8)) {
      var gate = new CountDownLatch(1);
      List<Future<HttpResponse<String>>> futures = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        futures.add(
            pool.submit(
                () -> {
                  gate.await();
                  return create(order, id());
                }));
      gate.countDown();
      Set<String> ids = new HashSet<>();
      int fresh = 0;
      for (var future : futures) {
        var response = future.get(30, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isIn(200, 201);
        if (response.statusCode() == 201) fresh++;
        ids.add(json.readTree(response.body()).get("task").get("id").asText());
      }
      assertThat(fresh).isEqualTo(1);
      assertThat(ids).hasSize(1);
      assertThat(scalar("agent_app", "SELECT count(*) FROM task WHERE order_id=?", order))
          .isEqualTo("1");
      assertThat(
              scalar(
                  "agent_app",
                  "SELECT count(*) FROM evidence WHERE task_id=?",
                  UUID.fromString(ids.iterator().next())))
          .isEqualTo("1");
      assertThat(
              scalar(
                  "agent_app",
                  "SELECT count(*) FROM audit_event WHERE task_id=?",
                  UUID.fromString(ids.iterator().next())))
          .isEqualTo("8");
    }
  }

  @Test
  void concurrentSameKeyIsIdempotentAndDifferentOrderConflicts() throws Exception {
    String order = order(), key = id();
    try (var pool = Executors.newFixedThreadPool(4)) {
      List<Future<HttpResponse<String>>> futures = new ArrayList<>();
      for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> create(order, key)));
      Set<String> tasks = new HashSet<>();
      for (var future : futures) {
        var response = future.get();
        assertThat(response.statusCode()).isIn(200, 201);
        tasks.add(json.readTree(response.body()).get("task").get("id").asText());
      }
      assertThat(tasks).hasSize(1);
      assertThat(
              scalar(
                  "agent_app",
                  "SELECT count(*) FROM audit_event WHERE task_id=?",
                  UUID.fromString(tasks.iterator().next())))
          .isEqualTo("1");
    }
    assertThat(data(create(order(), key), 409).get("code").asText())
        .isEqualTo("IDEMPOTENCY_CONFLICT");
  }

  @Test
  void authorizationCsrfAndValidationCannotLeakEvidenceOrCreateTasks() throws Exception {
    String order = order();
    String taskId = data(create(order, id()), 201).get("task").get("id").asText();
    data(http("GET", "/api/tasks/" + taskId + "/evidence", null, null, null, false), 401);
    data(http("GET", "/api/tasks/" + taskId + "/evidence", "unknown", null, null, false), 401);
    data(
        http("POST", "/api/tasks", "approver", id(), "{\"orderId\":\"" + order + "\"}", true), 403);
    data(
        http("POST", "/api/tasks", "operator", id(), "{\"orderId\":\"" + order + "\"}", false),
        403);
    data(create("OTHER-ORDER-SECRET", id()), 403);
    UUID hidden = UUID.randomUUID();
    update(
        "INSERT INTO task(id,order_id,state,created_by) VALUES(?,'OTHER-ORDER-SECRET','QUEUED','operator')",
        hidden);
    update(
        "INSERT INTO evidence(id,task_id,source,business_version,payload,fetched_at) VALUES(?,?,'SIMULATOR_ORDER',1,'{\"secret\":\"must-not-leak\"}',clock_timestamp())",
        UUID.randomUUID(),
        hidden);
    for (String suffix : List.of("", "/evidence", "/audit")) {
      var response = http("GET", "/api/tasks/" + hidden + suffix, "operator", null, null, false);
      data(response, 404);
      assertThat(response.body()).doesNotContain("SECRET", "must-not-leak");
    }
    assertThat(http("GET", "/api/tasks", "operator", null, null, false).body())
        .doesNotContain("OTHER-ORDER-SECRET");
    for (String bad :
        List.of(
            "{}",
            "{\"orderId\":null}",
            "{\"orderId\":\"../bad\"}",
            "{\"orderId\":\"" + order + "\",\"createdBy\":\"approver\"}"))
      data(http("POST", "/api/tasks", "operator", id(), bad, true), 400);
    data(
        http("POST", "/api/tasks", "operator", null, "{\"orderId\":\"" + order + "\"}", true), 400);
    data(create(order, "bad key"), 400);
    for (String query : List.of("?page=-1", "?size=101", "?state=INVALID", "?orderId=bad!"))
      data(http("GET", "/api/tasks" + query, "operator", null, null, false), 400);
    data(create("T05-ORDER-does-not-exist", id()), 404);
    assertThat(
            scalar(
                "agent_app", "SELECT count(*) FROM task WHERE order_id='T05-ORDER-does-not-exist'"))
        .isEqualTo("0");
  }

  @Test
  void listFiltersPaginationAndAuditCursorUsePersistedRecords() throws Exception {
    String order = order();
    var result = data(create(order, id()), 201);
    String taskId = result.get("task").get("id").asText();
    data(create(order, id()), 200);
    var page =
        data(
            http(
                "GET",
                "/api/tasks?orderId=" + order + "&state=QUEUED&size=1",
                "operator",
                null,
                null,
                false),
            200);
    assertThat(page.get("total").asLong()).isEqualTo(1);
    assertThat(page.get("items").size()).isEqualTo(1);
    assertThat(
            data(
                    http(
                        "GET",
                        "/api/tasks?orderId=" + order + "&page=1&size=1",
                        "operator",
                        null,
                        null,
                        false),
                    200)
                .get("items")
                .size())
        .isZero();
    assertThat(
            data(
                    http(
                        "GET",
                        "/api/tasks?orderId=" + order + "&state=FINISHED",
                        "operator",
                        null,
                        null,
                        false),
                    200)
                .get("total")
                .asLong())
        .isZero();
    var audit =
        data(
            http("GET", "/api/tasks/" + taskId + "/audit?limit=1", "operator", null, null, false),
            200);
    var next =
        data(
            http(
                "GET",
                "/api/tasks/" + taskId + "/audit?after=" + audit.get(0).get("id").asLong(),
                "operator",
                null,
                null,
                false),
            200);
    assertThat(next.size()).isEqualTo(1);
    assertThat(next.get(0).get("eventType").asText()).isEqualTo("TASK_REUSED");
  }

  @Test
  void auditFailureRollsBackTaskGuardEvidenceAndIdempotencyKey() throws Exception {
    String order = order(), key = id();
    update(
        "ALTER TABLE audit_event ADD CONSTRAINT t06_reject_creation CHECK(event_type <> 'TASK_CREATED') NOT VALID");
    try {
      data(create(order, key), 500);
      assertThat(scalar("agent_app", "SELECT count(*) FROM task WHERE order_id=?", order))
          .isEqualTo("0");
      assertThat(scalar("agent_app", "SELECT count(*) FROM order_guard WHERE order_id=?", order))
          .isEqualTo("0");
      assertThat(
              scalar(
                  "agent_app",
                  "SELECT count(*) FROM task_creation_request WHERE request_key=?",
                  key))
          .isEqualTo("0");
    } finally {
      update("ALTER TABLE audit_event DROP CONSTRAINT t06_reject_creation");
    }
    data(create(order, key), 201);
  }

  @Test
  void manualUnknownGuardStillBlocksNewTask() throws Exception {
    String order = order(), taskId = data(create(order, id()), 201).get("task").get("id").asText();
    update(
        "UPDATE task SET state='MANUAL',outcome='UNKNOWN',version=version+1 WHERE id=?",
        UUID.fromString(taskId));
    update("UPDATE order_guard SET reason='UNKNOWN_RESULT' WHERE order_id=?", order);
    var result = data(create(order, id()), 200);
    assertThat(result.get("task").get("id").asText()).isEqualTo(taskId);
    assertThat(result.get("task").get("state").asText()).isEqualTo("MANUAL");
    assertThat(scalar("agent_app", "SELECT count(*) FROM task WHERE order_id=?", order))
        .isEqualTo("1");
  }

  @Test
  void independentBackendRestartPreservesTaskAndDisablesDemoAccountsOutsideDemo() throws Exception {
    String order = order(),
        key = id(),
        taskId = data(create(order, key), 201).get("task").get("id").asText();
    String evidence =
        http("GET", "/api/tasks/" + taskId + "/evidence", "operator", null, null, false).body();
    backend.close();
    try {
      bootBackend("production");
      data(http("GET", "/api/me", "operator", null, null, false), 401);
    } finally {
      backend.close();
      bootBackend("test");
    }
    assertThat(data(create(order, key), 200).get("task").get("id").asText()).isEqualTo(taskId);
    assertThat(
            http("GET", "/api/tasks/" + taskId + "/evidence", "operator", null, null, false).body())
        .isEqualTo(evidence);
    data(http("GET", "/api/tasks/" + taskId + "/audit", "approver", null, null, false), 200);
  }

  @Test
  void databaseConstraintsPreventDuplicateActiveTasksAndOrphanGuards() throws Exception {
    String order = order();
    String taskId = data(create(order, id()), 201).get("task").get("id").asText();
    assertThatThrownBy(
            () ->
                update(
                    "INSERT INTO task(id,order_id,state,created_by) VALUES(?,?,'QUEUED','operator')",
                    UUID.randomUUID(),
                    order))
        .isInstanceOfSatisfying(
            SQLException.class, ex -> assertThat(ex.getSQLState()).isEqualTo("23505"));
    assertThatThrownBy(
            () ->
                update(
                    "INSERT INTO order_guard(order_id,task_id,reason) VALUES(?,?,'ACTIVE_TASK')",
                    "T05-ORDER-" + id(),
                    UUID.randomUUID()))
        .isInstanceOfSatisfying(
            SQLException.class, ex -> assertThat(ex.getSQLState()).isEqualTo("23503"));
    assertThat(scalar("agent_app", "SELECT task_id::text FROM order_guard WHERE order_id=?", order))
        .isEqualTo(taskId);
  }

  @Test
  void sessionAuthenticationAndCsrfProtectedLogoutWork() throws Exception {
    var operatorClient = accountClients.get("operator");
    try {
      var withoutBasic =
          HttpRequest.newBuilder(URI.create(base + "/api/me"))
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();
      assertThat(
              data(operatorClient.send(withoutBasic, HttpResponse.BodyHandlers.ofString()), 200)
                  .get("username")
                  .asText())
          .isEqualTo("operator");
      data(http("POST", "/api/auth/logout", "operator", null, null, false), 403);
      assertThat(http("POST", "/api/auth/logout", "operator", null, null, true).statusCode())
          .isEqualTo(204);
      data(operatorClient.send(withoutBasic, HttpResponse.BodyHandlers.ofString()), 401);
    } finally {
      csrfTokens.put(
          "operator",
          data(http("GET", "/api/auth/csrf", "operator", null, null, false), 200)
              .get("token")
              .asText());
    }
  }

  @Test
  void simulatorOutageDoesNotLoseExistingTasksOrLeavePartialNewTasks() throws Exception {
    String order = order(),
        key = id(),
        taskId = data(create(order, key), 201).get("task").get("id").asText();
    // 暂停网络对端，使真实 HttpClient 达到 3 秒请求超时；finally 必须恢复容器。
    var docker = org.testcontainers.DockerClientFactory.instance().client();
    docker.pauseContainerCmd(simulator.getContainerId()).exec();
    try {
      assertThat(data(create(order, key), 200).get("task").get("id").asText()).isEqualTo(taskId);
      assertThat(data(create(order, id()), 200).get("task").get("id").asText()).isEqualTo(taskId);
      data(http("GET", "/api/tasks/" + taskId + "/evidence", "operator", null, null, false), 200);
      String missing = "T05-ORDER-" + id();
      assertThat(data(create(missing, id()), 503).get("code").asText())
          .isEqualTo("SIMULATOR_UNAVAILABLE");
      assertThat(scalar("agent_app", "SELECT count(*) FROM task WHERE order_id=?", missing))
          .isEqualTo("0");
    } finally {
      docker.unpauseContainerCmd(simulator.getContainerId()).exec();
    }
  }
}
