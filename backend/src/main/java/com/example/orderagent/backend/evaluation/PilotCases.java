package com.example.orderagent.backend.evaluation;

import com.example.orderagent.backend.diagnosis.*;
import java.io.IOException;
import java.util.*;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class PilotCases {
  public static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  public enum Recommendation {
    NO_ACTION,
    WAIT,
    MANUAL,
    RECOMMEND_RECHECK
  }

  public enum Split {
    DEVELOPMENT,
    PILOT_HOLDOUT
  }

  public record Case(
      String id,
      Split split,
      String title,
      String orderId,
      Recommendation expected,
      List<String> requiredEvidence,
      JsonNode facts) {}

  public static List<Case> load() throws IOException {
    try (var input = PilotCases.class.getResourceAsStream("/evaluation/t03-cases.json")) {
      if (input == null) throw new IOException("缺少 T03 案例文件");
      var cases = List.of(JSON.readValue(input, Case[].class));
      if (cases.size() != 12 || cases.stream().map(Case::id).distinct().count() != 12)
        throw new IllegalArgumentException("案例数量或编号不合法");
      return cases;
    }
  }

  /** 这里只传事实和绑定订单。评分标签不进入工具，也不进入模型上下文。 */
  public static final class Evidence implements ProbeEvidenceSource {
    private final String orderId;
    private final JsonNode facts;

    public Evidence(String orderId, JsonNode facts) {
      this.orderId = orderId;
      this.facts = facts.deepCopy();
    }

    public String orderId() {
      return orderId;
    }

    public String source() {
      return "T03_SYNTHETIC";
    }

    public List<ToolCallback> definitions() {
      return new ProbeTools().definitions();
    }

    public String execute(String name, String arguments, String boundOrderId) {
      if (!orderId.equals(boundOrderId)) throw new IllegalArgumentException("INVALID_SCOPE");
      // 复用 T02 的严格参数验证，再读取当前绑定案例，不接触其他订单。
      var input = JSON.readTree(arguments);
      if (input == null
          || !input.isObject()
          || input.size() != 1
          || !input.path("orderId").isString()
          || !orderId.equals(input.path("orderId").asText())
          || !facts.has(name)) throw new IllegalArgumentException("INVALID_TOOL_ARGUMENTS");
      return JSON.writeValueAsString(
          Map.of(
              "source",
              source(),
              "orderId",
              orderId,
              "evidenceId",
              name,
              "data",
              facts.path(name)));
    }

    public Map<String, String> all() {
      Map<String, String> result = new TreeMap<>();
      for (String name :
          List.of("query_order", "query_payment", "query_callbacks", "query_history"))
        result.put(
            name, execute(name, JSON.writeValueAsString(Map.of("orderId", orderId)), orderId));
      return Collections.unmodifiableMap(result);
    }
  }
}
