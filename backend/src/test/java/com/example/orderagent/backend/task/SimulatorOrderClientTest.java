package com.example.orderagent.backend.task;

import static org.assertj.core.api.Assertions.*;

import com.example.orderagent.backend.api.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class SimulatorOrderClientTest {
  static final String ORDER =
      "{\"id\":\"T05-ORDER-1\",\"scenario\":\"CALLBACK_FAILED\",\"status\":\"PENDING_PAYMENT\",\"amountMinor\":10000,\"currency\":\"CNY\",\"paymentId\":\"T05-PAY-1\",\"version\":1}";

  @Test
  void rejectsMismatchedMalformedAndFractionalEvidence() throws Exception {
    for (String body :
        java.util.List.of(
            "not-json",
            ORDER.replace("T05-ORDER-1", "T05-ORDER-other"),
            ORDER.replace("10000", "10000.5"),
            ORDER.replace("PENDING_PAYMENT", "UNKNOWN"),
            ORDER.replace("\"version\":1", "\"version\":null"))) {
      withServer(
          200,
          body,
          client ->
              assertThatThrownBy(() -> client.fetch("T05-ORDER-1"))
                  .isInstanceOfSatisfying(
                      ApiException.class,
                      error -> {
                        assertThat(error.status()).isEqualTo(502);
                        assertThat(error.code()).isEqualTo("INVALID_ORDER_EVIDENCE");
                      }));
    }
  }

  @Test
  void distinguishesMissingFromUnavailableAndNeverRetriesOrFollowsRedirects() throws Exception {
    for (int status : java.util.List.of(404, 403, 500, 302)) {
      withServer(
          status,
          "internal credentials must not escape",
          client ->
              assertThatThrownBy(() -> client.fetch("T05-ORDER-1"))
                  .isInstanceOfSatisfying(
                      ApiException.class,
                      error -> {
                        assertThat(error.status()).isEqualTo(status == 404 ? 404 : 503);
                        assertThat(error.getMessage()).doesNotContain("credentials");
                      }));
    }
  }

  @Test
  void capturesOnlyRequestedOrderAndRequiresConfiguredReadCredential() throws Exception {
    withServer(
        200,
        ORDER,
        client -> {
          var snapshot = client.fetch("T05-ORDER-1");
          assertThat(snapshot.orderId()).isEqualTo("T05-ORDER-1");
          assertThat(snapshot.version()).isEqualTo(1);
        });
    var client = new SimulatorOrderClient(new MockEnvironment(), JsonMapper.builder().build());
    assertThatThrownBy(() -> client.fetch("T05-ORDER-1"))
        .isInstanceOfSatisfying(
            ApiException.class, error -> assertThat(error.status()).isEqualTo(503));
  }

  void withServer(int status, String body, java.util.function.Consumer<SimulatorOrderClient> action)
      throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var calls = new AtomicInteger();
    server.createContext(
        "/internal/orders/T05-ORDER-1",
        exchange -> {
          calls.incrementAndGet();
          assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
              .isEqualTo("Bearer ephemeral-test-read");
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          if (status == 302) exchange.getResponseHeaders().add("Location", "/must-not-follow");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      var env =
          new MockEnvironment()
              .withProperty(
                  "agent.simulator.base-url", "http://127.0.0.1:" + server.getAddress().getPort())
              .withProperty("agent.simulator.read-token", "ephemeral-test-read");
      action.accept(
          new SimulatorOrderClient(
              env,
              JsonMapper.builder().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build()));
      assertThat(calls.get()).isEqualTo(1);
    } finally {
      server.stop(0);
    }
  }
}
