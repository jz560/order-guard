package com.example.orderagent.backend.diagnosis;

import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;

/** T02 固定只读夹具；不连接业务数据库，不具备写凭证。 */
public final class ProbeTools {
  public static final String ORDER_ID = "T02-ORDER-001";
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final Map<String, String> DATA =
      Map.of(
          "query_order",
              "{\"status\":\"PENDING_PAYMENT\",\"paymentId\":\"T02-PAY-001\",\"amountMinor\":10000,\"currency\":\"CNY\",\"version\":1}",
          "query_payment",
              "{\"status\":\"SUCCESS\",\"paymentId\":\"T02-PAY-001\",\"amountMinor\":10000,\"currency\":\"CNY\"}",
          "query_callbacks",
              "{\"callbacks\":[{\"result\":\"FAILED\",\"errorCode\":\"ORDER_SERVICE_UNAVAILABLE\"}]}",
          "query_history", "{\"operations\":[]}");

  public List<ToolCallback> definitions() {
    return DATA.keySet().stream()
        .sorted()
        .map(
            name ->
                (ToolCallback)
                    new ToolCallback() {
                      public ToolDefinition getToolDefinition() {
                        return ToolDefinition.builder()
                            .name(name)
                            .description("只读查询当前订单的模拟证据：" + name)
                            .inputSchema(
                                "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}},\"required\":[\"orderId\"],\"additionalProperties\":false}")
                            .build();
                      }

                      public String call(String input) {
                        throw new IllegalStateException("工具只能由应用校验预算后显式执行");
                      }
                    })
        .toList();
  }

  public String execute(String name, String arguments, String boundOrderId) {
    if (!DATA.containsKey(name)) throw new IllegalArgumentException("UNKNOWN_TOOL");
    if (!ORDER_ID.equals(boundOrderId)) throw new IllegalArgumentException("INVALID_SCOPE");
    var input = JSON.readTree(arguments);
    if (input == null
        || !input.isObject()
        || input.size() != 1
        || !input.path("orderId").isString()
        || !boundOrderId.equals(input.path("orderId").asText())) {
      throw new IllegalArgumentException("INVALID_TOOL_ARGUMENTS");
    }
    return "{\"source\":\"T02_FIXTURE\",\"orderId\":\""
        + boundOrderId
        + "\",\"evidenceId\":\""
        + name
        + "\",\"data\":"
        + DATA.get(name)
        + "}";
  }
}
