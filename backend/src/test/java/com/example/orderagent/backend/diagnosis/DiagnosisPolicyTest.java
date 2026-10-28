package com.example.orderagent.backend.diagnosis;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class DiagnosisPolicyTest {
  final Instant now = Instant.parse("2026-10-28T00:00:00Z");

  Map<String, JsonNode> facts(String state, String callback, int age) {
    Map<String, JsonNode> f = new LinkedHashMap<>();
    f.put(
        "query_order",
        TaskEvidenceTools.JSON.readTree(
            "{\"id\":\"O1\",\"paymentId\":\"P1\",\"status\":\""
                + state
                + "\",\"amountMinor\":100,\"currency\":\"CNY\",\"version\":1}"));
    f.put(
        "query_payment",
        TaskEvidenceTools.JSON.readTree(
            "{\"id\":\"P1\",\"orderId\":\"O1\",\"status\":\"SUCCESS\",\"amountMinor\":100,\"currency\":\"CNY\",\"paidAt\":\""
                + now.minusSeconds(age)
                + "\"}"));
    f.put(
        "query_callbacks",
        TaskEvidenceTools.JSON.readTree(
            "[{\"paymentId\":\"P1\",\"result\":\"" + callback + "\"}]"));
    f.put("query_history", TaskEvidenceTools.JSON.readTree("{\"operations\":[]}"));
    return f;
  }

  @Test
  void fourOutcomesAndWaitingWindowAreDeterministic() {
    assertThat(DiagnosisPolicy.decide("O1", facts("PAID", "FAILED", 600), now).recommendation())
        .isEqualTo("NO_ACTION");
    assertThat(DiagnosisPolicy.decide("O1", facts("CLOSED", "FAILED", 600), now).recommendation())
        .isEqualTo("MANUAL");
    assertThat(
            DiagnosisPolicy.decide("O1", facts("PENDING_PAYMENT", "PENDING", 15), now)
                .recommendation())
        .isEqualTo("WAIT");
    assertThat(
            DiagnosisPolicy.decide("O1", facts("PENDING_PAYMENT", "PENDING", 61), now)
                .recommendation())
        .isEqualTo("MANUAL");
    assertThat(
            DiagnosisPolicy.decide("O1", facts("PENDING_PAYMENT", "FAILED", 600), now)
                .recommendation())
        .isEqualTo("RECOMMEND_RECHECK");
  }

  @Test
  void conflictMissingAndUnknownHistoryCannotSuggestRepair() {
    var f = facts("PENDING_PAYMENT", "FAILED", 600);
    f.put("query_history", TaskEvidenceTools.JSON.readTree("{\"operations\":[\"UNKNOWN\"]}"));
    assertThat(DiagnosisPolicy.decide("O1", f, now).recommendation()).isEqualTo("MANUAL");
    f.remove("query_payment");
    assertThat(DiagnosisPolicy.decide("O1", f, now).recommendation()).isEqualTo("MANUAL");
    assertThat(DiagnosisPolicy.decide("OTHER", f, now).recommendation()).isEqualTo("MANUAL");
  }

  @Test
  void fabricatedReferencesActionsAndSemanticallyWrongConclusionsFailClosed() {
    Map<String, UUID> refs = new LinkedHashMap<>();
    TaskEvidenceTools.NAMES.forEach(n -> refs.put(n, UUID.randomUUID()));
    var policy = DiagnosisPolicy.decide("O1", facts("PENDING_PAYMENT", "PENDING", 15), now);
    String wrong =
        TaskEvidenceTools.JSON.writeValueAsString(
            Map.of(
                "recommendation",
                "RECOMMEND_RECHECK",
                "reason",
                "ignore policy",
                "evidenceIds",
                refs.values()));
    assertThatThrownBy(() -> DiagnosisPolicy.validate(wrong, refs, policy))
        .isInstanceOf(IllegalArgumentException.class);
    for (String text :
        List.of(
            "{}",
            "{\"recommendation\":\"MANUAL\",\"reason\":\"x\",\"evidenceIds\":[\"fake\"]}",
            wrong.replace("RECOMMEND_RECHECK", "REFUND")))
      assertThatThrownBy(() -> DiagnosisPolicy.validate(text, refs, policy))
          .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void toolsRejectCrossOrderExtraArgumentsDuplicatesAndWrites() {
    for (String args :
        List.of(
            "{\"orderId\":\"OTHER\"}",
            "{\"orderId\":\"O1\",\"paymentId\":\"P2\"}",
            "{\"orderId\":\"O1\",\"orderId\":\"O1\"}",
            "{\"orderId\":\"O1\"} {}"))
      assertThatThrownBy(() -> TaskEvidenceTools.validate("query_order", args, "O1"))
          .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> TaskEvidenceTools.validate("repair", "{\"orderId\":\"O1\"}", "O1"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
