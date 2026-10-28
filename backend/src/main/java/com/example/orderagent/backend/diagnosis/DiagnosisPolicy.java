package com.example.orderagent.backend.diagnosis;

import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class DiagnosisPolicy {
  public static final String VERSION = "t11-v1";

  public record Verdict(String recommendation, String reason) {}

  public static Verdict decide(String order, Map<String, JsonNode> f, Instant now) {
    var o = f.get("query_order");
    var p = f.get("query_payment");
    var c = f.get("query_callbacks");
    var h = f.get("query_history");
    if (o == null || !order.equals(o.path("id").asText())) return manual("订单证据不足");
    if ("PAID".equals(o.path("status").asText())) return new Verdict("NO_ACTION", "订单已支付，无需处理");
    if (!"PENDING_PAYMENT".equals(o.path("status").asText())) return manual("订单状态不允许处理");
    if (p == null
        || !order.equals(p.path("orderId").asText())
        || !o.path("paymentId").equals(p.path("id"))
        || !o.path("amountMinor").isIntegralNumber()
        || o.path("amountMinor").asLong() <= 0
        || !o.path("amountMinor").equals(p.path("amountMinor"))
        || !"CNY".equals(o.path("currency").asText())
        || !o.path("currency").equals(p.path("currency"))) return manual("支付缺失或关联、金额、币种冲突");
    if ("FAILED".equals(p.path("status").asText())) return new Verdict("NO_ACTION", "支付失败，不存在目标异常");
    if (!"SUCCESS".equals(p.path("status").asText()) || c == null || !c.isArray() || c.isEmpty())
      return manual("支付或回调证据不足");
    Instant paid;
    try {
      paid = Instant.parse(p.path("paidAt").asText());
    } catch (RuntimeException e) {
      return manual("支付时间证据不足");
    }
    long age = Duration.between(paid, now).getSeconds();
    if (age < 0) return manual("支付时间异常");
    boolean pending = false, failed = false;
    for (var callback : c) {
      if (!p.path("id").equals(callback.path("paymentId"))) return manual("回调关联不一致");
      pending |= "PENDING".equals(callback.path("result").asText());
      failed |= "FAILED".equals(callback.path("result").asText());
      if (!Set.of("PENDING", "FAILED").contains(callback.path("result").asText()))
        return manual("回调状态需人工核对");
    }
    if (pending && age <= 60) return new Verdict("WAIT", "支付成功后仍在回调等待窗口");
    if (pending
        || !failed
        || h == null
        || !h.path("operations").isArray()
        || !h.path("operations").isEmpty()) return manual("回调或历史操作需人工核对");
    if (!o.path("version").isIntegralNumber()
        || o.path("version").asLong() < 1
        || o.path("version").asLong() == Long.MAX_VALUE) return manual("订单版本无效");
    return new Verdict("RECOMMEND_RECHECK", "支付成功、金额关联一致、回调失败且无历史执行，建议审批后重新核验");
  }

  private static Verdict manual(String reason) {
    return new Verdict("MANUAL", reason);
  }

  public static JsonNode validate(String text, Map<String, UUID> refs, Verdict policy) {
    var n = TaskEvidenceTools.JSON.readTree(text);
    if (n == null
        || !n.isObject()
        || n.size() != 3
        || !n.path("recommendation").isString()
        || !n.path("reason").isString()
        || n.path("reason").asText().isBlank()
        || n.path("reason").asText().length() > 1000
        || !n.path("evidenceIds").isArray())
      throw new IllegalArgumentException("INVALID_DIAGNOSIS");
    String recommendation = n.path("recommendation").asText();
    if (!Set.of("NO_ACTION", "WAIT", "MANUAL", "RECOMMEND_RECHECK").contains(recommendation))
      throw new IllegalArgumentException("INVALID_ACTION");
    Set<String> cited = new HashSet<>();
    var allowed = refs.values().stream().map(UUID::toString).toList();
    for (var ref : n.path("evidenceIds"))
      if (!ref.isString() || !allowed.contains(ref.asText()) || !cited.add(ref.asText()))
        throw new IllegalArgumentException("INVALID_REFERENCE");
    if (!recommendation.equals("MANUAL")
        && (!recommendation.equals(policy.recommendation())
            || refs.size() != 4
            || cited.size() != 4)) throw new IllegalArgumentException("UNSUPPORTED_CONCLUSION");
    return n;
  }
}
