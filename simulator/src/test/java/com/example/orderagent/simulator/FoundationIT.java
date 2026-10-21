package com.example.orderagent.simulator;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

class FoundationIT {
  @Test
  void bootsWithoutModelKeyMigratesOnceAndEnforcesDatabaseIsolation() throws Exception {
    var initScript = Path.of("../infra/postgres/init-databases.sh").toAbsolutePath().normalize();
    try (var postgres =
        new PostgreSQLContainer<>("postgres:17.6-bookworm")
            .withDatabaseName("postgres")
            .withUsername("postgres")
            .withPassword("ephemeral-test-admin")
            .withEnv("BACKEND_DB_PASSWORD", "ephemeral-test-backend")
            .withEnv("SIMULATOR_DB_PASSWORD", "ephemeral-test-simulator")
            .withCopyFileToContainer(
                MountableFile.forHostPath(initScript, 0755),
                "/docker-entrypoint-initdb.d/10-databases.sh")) {
      // 不设置 disabledWithoutDocker；缺少 Docker 必须导致集成验证失败。
      postgres.start();
      String urlPrefix =
          "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/";
      String password = "ephemeral-test-simulator";
      assertThatThrownBy(
              () -> {
                try (var ignored =
                    DriverManager.getConnection(
                        urlPrefix + "agent_app", "simulator_app", password)) {
                  fail("不应允许访问另一服务的数据库");
                }
              })
          .isInstanceOf(SQLException.class)
          .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("42501"));

      Object initializedAt = null;
      for (int start = 0; start < 2; start++) {
        try (var context =
            new SpringApplicationBuilder(SimulatorApplication.class)
                .run(
                    "--server.port=0",
                    "--spring.profiles.active=test",
                    "--spring.datasource.url=" + urlPrefix + "agent_simulator",
                    "--spring.datasource.username=simulator_app",
                    "--spring.datasource.password=" + password,
                    "--agent.model.mode=stub")) {
          var jdbc = context.getBean(JdbcTemplate.class);
          assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
              .isEqualTo("agent_simulator");
          assertThat(jdbc.queryForObject("SELECT current_user", String.class))
              .isEqualTo("simulator_app");
          assertThat(jdbc.queryForObject("SELECT service_name FROM service_identity", String.class))
              .isEqualTo("simulator");
          assertThat(
                  jdbc.queryForObject(
                      "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class))
              .isEqualTo(1);
          Object current =
              jdbc.queryForObject("SELECT initialized_at FROM service_identity", Object.class);
          if (start == 0) initializedAt = current;
          else assertThat(current).isEqualTo(initializedAt);

          String base =
              "http://localhost:" + context.getEnvironment().getProperty("local.server.port");
          var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
          var health =
              client.send(
                  HttpRequest.newBuilder(URI.create(base + "/actuator/health"))
                      .timeout(Duration.ofSeconds(10))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
          assertThat(health.statusCode()).isEqualTo(200);
          assertThat(health.body())
              .contains("\"status\":\"UP\"")
              .doesNotContain("components", "password");
          var privateEndpoint =
              client.send(
                  HttpRequest.newBuilder(URI.create(base + "/actuator/env"))
                      .timeout(Duration.ofSeconds(10))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
          assertThat(privateEndpoint.statusCode()).isEqualTo(403);
        }
      }
    }
  }
}
