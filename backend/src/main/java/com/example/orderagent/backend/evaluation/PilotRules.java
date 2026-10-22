package com.example.orderagent.backend.evaluation;

import static com.example.orderagent.backend.evaluation.PilotCases.Recommendation.*;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** 完整事实下的确定性对照，使用与模型相同的政策，不读取案例标签。 */
public final class PilotRules {
  public static final String POLICY =
      """
      这是 T03 固定政策实验，不执行写操作，按以下优先级诊断：
      1. 订单 PAID -> NO_ACTION；CLOSED -> MANUAL；订单缺失或未知状态 -> MANUAL。
      2. 待支付订单缺少支付，或支付与订单 ID、paymentId、整数金额、币种不一致 -> MANUAL。
      3. 支付 PROCESSING 且 ageSeconds <=120 -> WAIT；超过120 -> MANUAL。支付 FAILED -> NO_ACTION。
      4. 支付 SUCCESS：若回调证据缺失 -> MANUAL；回调 PENDING 且支付 ageSeconds <=60 -> WAIT。
      5. 其余 SUCCESS 情形须查询历史：历史不可用、历史含 UNKNOWN/PROCESSING/SUCCEEDED -> MANUAL。
      6. SUCCESS、回调 FAILED、历史 operations 为空、四类证据完整 -> RECOMMEND_RECHECK，仅建议重新核验并等待审批。
      7. 其他情形 -> MANUAL。字段 available=false 表示证据不可用，不等于业务事实不存在。
      只引用已获得的 evidenceId。简短 reason 需说明决定结论的事实；不输出内部推理。
      可以在已支付、已关闭、支付冲突等早期条件明确时停止查询，避免无关查询；修复建议必须取得四类证据。
      """;

  public record Verdict(
      PilotCases.Recommendation recommendation, String reason, List<String> evidenceIds) {
    public JsonNode json() {
      return PilotCases.JSON.valueToTree(
          Map.of(
              "recommendation",
              recommendation.name(),
              "reason",
              reason,
              "evidenceIds",
              evidenceIds));
    }
  }

  public static Verdict diagnose(JsonNode facts) {
    var o = facts.path("query_order");
    var p = facts.path("query_payment");
    var c = facts.path("query_callbacks");
    var h = facts.path("query_history");
    var op = List.of("query_order", "query_payment");
    if ("PAID".equals(o.path("status").asText()))
      return new Verdict(NO_ACTION, "订单已支付", List.of("query_order"));
    if (!"PENDING_PAYMENT".equals(o.path("status").asText()))
      return new Verdict(MANUAL, "订单状态不允许处理", List.of("query_order"));
    if (!p.has("status")
        || !o.path("orderId").equals(p.path("orderId"))
        || !o.path("paymentId").equals(p.path("paymentId"))
        || !o.path("amountMinor").equals(p.path("amountMinor"))
        || !o.path("currency").equals(p.path("currency")))
      return new Verdict(MANUAL, "支付证据缺失或关联、金额、币种冲突", op);
    if ("PROCESSING".equals(p.path("status").asText()))
      return new Verdict(p.path("ageSeconds").asInt(121) <= 120 ? WAIT : MANUAL, "按支付处理时间窗口判断", op);
    if ("FAILED".equals(p.path("status").asText()))
      return new Verdict(NO_ACTION, "支付失败，不存在目标异常", op);
    var opc = List.of("query_order", "query_payment", "query_callbacks");
    if (!"SUCCESS".equals(p.path("status").asText()) || !c.has("status"))
      return new Verdict(MANUAL, "支付或回调证据不足", opc);
    if ("PENDING".equals(c.path("status").asText()) && p.path("ageSeconds").asInt(61) <= 60)
      return new Verdict(WAIT, "支付成功后仍在回调等待窗口", opc);
    var all = List.of("query_order", "query_payment", "query_callbacks", "query_history");
    if ("FAILED".equals(c.path("status").asText())
        && h.path("operations").isArray()
        && h.path("operations").isEmpty())
      return new Verdict(RECOMMEND_RECHECK, "支付成功、信息一致、回调失败且无历史操作，建议审批后重新核验", all);
    return new Verdict(MANUAL, "历史操作或回调状态需要人工核对", all);
  }
}
