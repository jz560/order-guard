package com.example.orderagent.backend.diagnosis;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 只持有只读凭证，支付标识只能从绑定订单的已保存证据取得。 */
@Component
public class TaskEvidenceTools {
  public static List<org.springframework.ai.tool.ToolCallback> definitions() {
    return NAMES.stream()
        .map(
            name ->
                (org.springframework.ai.tool.ToolCallback)
                    new org.springframework.ai.tool.ToolCallback() {
                      public org.springframework.ai.tool.definition.ToolDefinition
                          getToolDefinition() {
                        return org.springframework.ai.tool.definition.ToolDefinition.builder()
                            .name(name)
                            .description("只读查询绑定任务订单的证据；query_history 查询本系统历史执行记录")
                            .inputSchema(
                                "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},\"required\":[\"orderId\"],\"additionalProperties\":false}")
                            .build();
                      }

                      public String call(String input) {
                        throw new IllegalStateException("工具必须由应用验证并记录意图后执行");
                      }
                    })
        .toList();
  }

  public static final List<String> NAMES =
      List.of("query_order", "query_payment", "query_callbacks", "query_history");
  static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final URI base;
  private final String token;
  private final DiagnosisJournal journal;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public TaskEvidenceTools(Environment env, DiagnosisJournal journal) {
    base = URI.create(env.getProperty("agent.simulator.base-url", "http://localhost:8081"));
    if (!Set.of("http", "https").contains(base.getScheme())
        || base.getHost() == null
        || base.getUserInfo() != null
        || base.getQuery() != null
        || base.getFragment() != null
        || !(base.getPath().isEmpty() || base.getPath().equals("/")))
      throw new IllegalArgumentException("模拟服务地址无效");
    token = env.getProperty("agent.simulator.read-token", "");
    this.journal = journal;
  }

  public static void validate(String name, String args, String order) {
    if (!NAMES.contains(name) || args == null || args.length() > 2048)
      throw new IllegalArgumentException("INVALID_TOOL");
    var n = JSON.readTree(args);
    if (n == null
        || !n.isObject()
        || n.size() != 1
        || !n.path("orderId").isString()
        || !order.equals(n.path("orderId").asText()))
      throw new IllegalArgumentException("INVALID_SCOPE");
  }

  public JsonNode fetch(String name, String order, Map<String, JsonNode> facts) {
    if (!order.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("INVALID_SCOPE");
    if (name.equals("query_history"))
      return JSON.valueToTree(Map.of("operations", journal.history(order)));
    String path = "/internal/orders/" + order;
    if (!name.equals("query_order")) {
      var o = facts.get("query_order");
      if (o == null || !order.equals(o.path("id").asText()))
        throw new IllegalArgumentException("ORDER_REQUIRED");
      String payment = o.path("paymentId").asText();
      if (!payment.matches("[A-Za-z0-9_-]{1,100}"))
        throw new IllegalArgumentException("PAYMENT_REQUIRED");
      path = "/internal/payments/" + payment + (name.equals("query_callbacks") ? "/callbacks" : "");
    }
    if (token.isBlank()) return JSON.valueToTree(Map.of("available", false));
    try {
      var response =
          client.send(
              HttpRequest.newBuilder(base.resolve(path))
                  .timeout(Duration.ofSeconds(3))
                  .header("Authorization", "Bearer " + token)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200 || response.body().length() > 65536)
        return JSON.valueToTree(Map.of("available", false));
      var result = JSON.readTree(response.body());
      if (result == null) throw new IllegalArgumentException("INVALID_EVIDENCE");
      return result;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("INTERRUPTED");
    } catch (java.io.IOException e) {
      return JSON.valueToTree(Map.of("available", false));
    }
  }
}
