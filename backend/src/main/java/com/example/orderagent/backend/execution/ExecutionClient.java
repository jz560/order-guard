package com.example.orderagent.backend.execution;

import com.example.orderagent.backend.execution.ExecutionRecords.*;
import com.example.orderagent.backend.task.SimulatorOrderClient.Order;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ExecutionClient {
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final URI base;
  private final String readToken, executeToken;
  private final Duration timeout;
  private final JsonMapper json;

  public ExecutionClient(Environment env, JsonMapper json) {
    this.json = json;
    base = URI.create(env.getProperty("agent.simulator.base-url", "http://localhost:8081"));
    if (!Set.of("http", "https").contains(base.getScheme())
        || base.getHost() == null
        || base.getUserInfo() != null
        || base.getQuery() != null
        || base.getFragment() != null
        || !(base.getPath().isEmpty() || base.getPath().equals("/")))
      throw new IllegalArgumentException("模拟服务地址无效");
    readToken = env.getProperty("agent.simulator.read-token", "");
    executeToken = env.getProperty("agent.simulator.execute-token", "");
    long millis = env.getProperty("agent.execution.http-timeout-ms", Long.class, 3000L);
    if (millis < 100 || millis > 5000)
      throw new IllegalArgumentException("执行 HTTP 超时须为 100—5000ms");
    timeout = Duration.ofMillis(millis);
  }

  public boolean configured() {
    return !readToken.isBlank() && !executeToken.isBlank();
  }

  public Facts facts(Params params) {
    var order =
        request("GET", "/internal/orders/" + params.orderId(), readToken, null, Order.class);
    var payment =
        request("GET", "/internal/payments/" + params.paymentId(), readToken, null, Payment.class);
    return new Facts(order, payment, Instant.now());
  }

  public Operation send(UUID operationId, Params params) {
    return request(
        "PUT",
        "/internal/operations/" + operationId,
        executeToken,
        json.writeValueAsString(params),
        Operation.class);
  }

  public Operation query(UUID operationId) {
    return request(
        "GET", "/internal/operations/" + operationId, executeToken, null, Operation.class);
  }

  private <T> T request(String method, String path, String token, String body, Class<T> type) {
    if (token.isBlank()) throw new Unavailable();
    try {
      var request =
          HttpRequest.newBuilder(base.resolve(path))
              .timeout(timeout)
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json")
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(body))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200 && !(method.equals("PUT") && response.statusCode() == 422))
        throw new Unavailable();
      if (response.body().length() > 65536) throw new Unavailable();
      try {
        var value = json.readValue(response.body(), type);
        if (value == null) throw new Unavailable();
        return value;
      } catch (RuntimeException e) {
        throw new Unavailable();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Unavailable();
    } catch (IOException e) {
      throw new Unavailable();
    }
  }

  public static class Unavailable extends RuntimeException {
    public Unavailable() {
      super("业务查询或响应暂不可确认");
    }
  }
}
