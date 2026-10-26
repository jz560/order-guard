package com.example.orderagent.backend.task;

import com.example.orderagent.backend.api.ApiException;
import com.example.orderagent.backend.task.TaskRecords.Snapshot;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SimulatorOrderClient {
  public record Order(
      String id,
      String scenario,
      String status,
      Long amountMinor,
      String currency,
      String paymentId,
      Long version) {}

  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final URI base;
  private final String token;
  private final JsonMapper json;

  public SimulatorOrderClient(Environment env, JsonMapper json) {
    this.json = json;
    base = URI.create(env.getProperty("agent.simulator.base-url", "http://localhost:8081"));
    if (!java.util.Set.of("http", "https").contains(base.getScheme())
        || base.getHost() == null
        || base.getUserInfo() != null
        || base.getQuery() != null
        || base.getFragment() != null
        || !(base.getPath().isEmpty() || base.getPath().equals("/")))
      throw new IllegalArgumentException("模拟服务地址须为不含凭证和路径的 HTTP(S) 服务地址");
    token = env.getProperty("agent.simulator.read-token", "");
  }

  public Snapshot fetch(String orderId) {
    if (token.isBlank()) throw new ApiException(503, "SIMULATOR_UNAVAILABLE", "尚未配置模拟服务只读凭证");
    var request =
        HttpRequest.newBuilder(base.resolve("/internal/orders/" + orderId))
            .timeout(Duration.ofSeconds(3))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
    try {
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404) throw new ApiException(404, "ORDER_NOT_FOUND", "订单不存在");
      if (response.statusCode() != 200)
        throw new ApiException(503, "SIMULATOR_UNAVAILABLE", "订单查询暂不可用，请稍后重试");
      if (response.body().length() > 65536)
        throw new ApiException(502, "INVALID_ORDER_EVIDENCE", "订单证据格式不正确");
      Order order;
      try {
        order = json.readValue(response.body(), Order.class);
      } catch (RuntimeException ex) {
        throw new ApiException(502, "INVALID_ORDER_EVIDENCE", "订单证据格式不正确");
      }
      if (order == null
          || !orderId.equals(order.id())
          || order.version() == null
          || order.version() < 1
          || order.amountMinor() == null
          || order.amountMinor() < 1
          || !"CNY".equals(order.currency())
          || order.paymentId() == null
          || !order.paymentId().matches("[A-Za-z0-9_-]{1,100}")
          || order.status() == null
          || !java.util.Set.of("PENDING_PAYMENT", "PAID", "CLOSED").contains(order.status()))
        throw new ApiException(502, "INVALID_ORDER_EVIDENCE", "订单证据字段不一致");
      return new Snapshot(orderId, order.version(), json.writeValueAsString(order), Instant.now());
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ApiException(503, "SIMULATOR_UNAVAILABLE", "订单查询已中断");
    } catch (IOException ex) {
      throw new ApiException(503, "SIMULATOR_UNAVAILABLE", "订单查询超时或连接不可用");
    }
  }
}
