package com.example.orderagent.backend.diagnosis;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DeepSeekModelGatewayTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void realAdapterSerializesToolsAndReturnsToApplicationBetweenRounds() throws Exception {
    var requests = new CopyOnWriteArrayList<JsonNode>();
    var paths = new CopyOnWriteArrayList<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          paths.add(exchange.getRequestURI().getPath());
          requests.add(
              JSON.readTree(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
          String message =
              requests.size() == 1
                  ? "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"query_order\",\"arguments\":\"{\\\"orderId\\\":\\\"T02-ORDER-001\\\"}\"}}]}"
                  : "{\"role\":\"assistant\",\"content\":\"{\\\"recommendation\\\":\\\"MANUAL\\\",\\\"reason\\\":\\\"需要支付证据\\\",\\\"evidenceIds\\\":[\\\"query_order\\\"]}\"}";
          String body =
              "{\"id\":\"response-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,\"finish_reason\":\""
                  + (requests.size() == 1 ? "tool_calls" : "stop")
                  + "\",\"message\":"
                  + message
                  + "}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":40,\"total_tokens\":140}}";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      var events = new ArrayList<Map<String, Object>>();
      var failures = new CopyOnWriteArrayList<Throwable>();
      var gateway =
          new DeepSeekModelGateway(
              "local-test-key",
              "http://127.0.0.1:" + server.getAddress().getPort(),
              Duration.ofSeconds(2));
      var result =
          new ProbeLoop(
                  p -> {
                    try {
                      return gateway.call(p);
                    } catch (RuntimeException failure) {
                      failures.add(failure);
                      throw failure;
                    }
                  },
                  new ProbeTools(),
                  ProbeLoop.Limits.defaults(),
                  events::add)
              .run(ProbeTools.ORDER_ID);
      assertThat(failures).isEmpty();
      assertThat(result.status())
          .withFailMessage("result=%s events=%s requests=%s", result, events, requests)
          .isEqualTo("COMPLETED");
      assertThat(requests).hasSize(2);
      assertThat(paths).allMatch(p -> p.endsWith("/chat/completions"));
      assertThat(requests.getFirst().path("model").asText()).isEqualTo("deepseek-flash");
      assertThat(requests.getFirst().path("thinking").path("type").asText()).isEqualTo("disabled");
      assertThat(requests.getFirst().path("max_tokens").asInt()).isEqualTo(1000);
      assertThat(requests.getFirst().path("tools").size()).isEqualTo(4);
      assertThat(requests.get(1).path("messages").toString())
          .contains("tool_call_id", "call-1", "T02_FIXTURE");
      assertThat(events.toString())
          .contains("inputTokens=100", "outputTokens=40")
          .doesNotContain("local-test-key");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rateLimitIsNotRetried() throws Exception {
    var requests = new java.util.concurrent.atomic.AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          var bytes =
              "{\"error\":{\"message\":\"rate limited\",\"type\":\"rate_limit_error\"}}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(429, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    try {
      var gateway =
          new DeepSeekModelGateway(
              "local-test-key",
              "http://127.0.0.1:" + server.getAddress().getPort(),
              Duration.ofSeconds(1));
      assertThatThrownBy(() -> gateway.call(new Prompt("test")))
          .isInstanceOf(RuntimeException.class);
      assertThat(requests.get()).isEqualTo(1);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void realModeRequiresKeyWhileStubRemainsKeyless() {
    assertThatThrownBy(() -> new ModelConfiguration().modelGateway("deepseek", ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new ModelConfiguration().modelGateway("stub", ""))
        .isInstanceOf(StubModelGateway.class);
  }
}
