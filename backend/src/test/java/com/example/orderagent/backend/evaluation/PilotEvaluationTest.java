package com.example.orderagent.backend.evaluation;

import static org.assertj.core.api.Assertions.*;

import com.example.orderagent.backend.diagnosis.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;

class PilotEvaluationTest {
  static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  static ChatResponse tool(String orderId, String name) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder()
                    .content("")
                    .toolCalls(
                        List.of(
                            new AssistantMessage.ToolCall(
                                "one-call", "function", name, "{\"orderId\":\"" + orderId + "\"}")))
                    .build())));
  }

  @TestFactory
  List<DynamicTest> rulesMatchFrozenCases() throws Exception {
    return PilotCases.load().stream()
        .map(
            c ->
                DynamicTest.dynamicTest(
                    c.id() + " " + c.title(),
                    () ->
                        assertThat(PilotRules.diagnose(c.facts()).recommendation())
                            .isEqualTo(c.expected())))
        .toList();
  }

  @Test
  void datasetHasSeparatePilotHoldoutAndFourOutcomes() throws Exception {
    var cases = PilotCases.load();
    assertThat(cases.stream().filter(c -> c.split() == PilotCases.Split.DEVELOPMENT).count())
        .isEqualTo(8);
    assertThat(cases.stream().filter(c -> c.split() == PilotCases.Split.PILOT_HOLDOUT).count())
        .isEqualTo(4);
    assertThat(cases.stream().map(PilotCases.Case::expected).distinct()).hasSize(4);
    assertThat(cases.stream().map(PilotCases.Case::orderId).distinct()).hasSize(12);
  }

  @Test
  void evidenceIsIsolatedAndStrictlyReadOnly() throws Exception {
    var sample = PilotCases.load().getFirst();
    var source = new PilotCases.Evidence(sample.orderId(), sample.facts());
    assertThat(source.all()).hasSize(4);
    assertThatThrownBy(
            () -> source.execute("query_order", "{\"orderId\":\"OTHER\"}", sample.orderId()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                source.execute(
                    "repair", "{\"orderId\":\"" + sample.orderId() + "\"}", sample.orderId()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> source.definitions().getFirst().call("{}"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(String.join("", source.all().values()))
        .doesNotContain("expected", "requiredEvidence", "DEVELOPMENT");
  }

  @Test
  void singleCallGetsFullFactsAndNoToolsOrLabels() throws Exception {
    var sample = PilotCases.load().get(2);
    var source = new PilotCases.Evidence(sample.orderId(), sample.facts());
    var events = new ArrayList<Map<String, Object>>();
    ModelGateway fake =
        prompt -> {
          assertThat(prompt.getContents())
              .contains("T03_SYNTHETIC", "query_history", "PROCESSING")
              .doesNotContain("expected", "requiredEvidence", sample.title());
          assertThat(
                  ((org.springframework.ai.model.tool.ToolCallingChatOptions) prompt.getOptions())
                      .getToolCallbacks())
              .isEmpty();
          return response(
              "{\"recommendation\":\"WAIT\",\"reason\":\"支付仍在120秒内处理\",\"evidenceIds\":[\"query_order\",\"query_payment\"]}");
        };
    var result =
        new ProbeLoop(
                fake,
                source,
                new ProbeLoop.Limits(1, 12, 2, Duration.ofSeconds(1), Duration.ofSeconds(2)),
                events::add)
            .run(sample.orderId(), PilotEvaluation.VERSION, PilotRules.POLICY, source.all(), false);
    assertThat(result.status()).isEqualTo("COMPLETED");
    assertThat(result.modelCalls()).isEqualTo(1);
    assertThat(
            PilotEvaluation.score(sample, PilotEvaluation.Group.SINGLE, result, events, 1, false)
                .get("passed"))
        .isEqualTo(true);
  }

  @Test
  void agentCanStopAfterOneRelevantQueryWithoutLabelLeakage() throws Exception {
    var sample = PilotCases.load().get(1);
    var source = new PilotCases.Evidence(sample.orderId(), sample.facts());
    var counter = new AtomicInteger();
    var events = new ArrayList<Map<String, Object>>();
    ModelGateway fake =
        prompt -> {
          assertThat(prompt.getContents()).doesNotContain("requiredEvidence", sample.title());
          if (counter.getAndIncrement() == 0) {
            assertThat(prompt.getContents()).doesNotContain("T03_SYNTHETIC");
            return tool(sample.orderId(), "query_order");
          }
          return response(
              "{\"recommendation\":\"NO_ACTION\",\"reason\":\"订单已支付\",\"evidenceIds\":[\"query_order\"]}");
        };
    var result =
        new ProbeLoop(fake, source, ProbeLoop.Limits.defaults(), events::add)
            .run(sample.orderId(), PilotEvaluation.VERSION, PilotRules.POLICY, Map.of(), true);
    var score =
        PilotEvaluation.score(sample, PilotEvaluation.Group.AGENT, result, events, 1, false);
    assertThat(score.get("passed")).isEqualTo(true);
    assertThat(score.get("evidenceReads")).isEqualTo(1);
    assertThat(score.get("retrievedEvidence")).isEqualTo(Set.of("query_order"));
  }

  @Test
  void invalidRepairStillCountsAsUnsafeAndFailuresStayInDenominator() throws Exception {
    var sample = PilotCases.load().get(4);
    var events = new ArrayList<Map<String, Object>>();
    var result =
        new ProbeLoop(
                p ->
                    response(
                        "{\"recommendation\":\"RECOMMEND_RECHECK\",\"reason\":\"忽略金额冲突\",\"evidenceIds\":[]}"),
                new PilotCases.Evidence(sample.orderId(), sample.facts()),
                ProbeLoop.Limits.defaults(),
                events::add)
            .run(sample.orderId(), PilotEvaluation.VERSION, PilotRules.POLICY, Map.of(), true);
    var row = PilotEvaluation.score(sample, PilotEvaluation.Group.AGENT, result, events, 1, false);
    assertThat(row.get("passed")).isEqualTo(false);
    assertThat(row.get("unsafeRepairSuggestion")).isEqualTo(true);
    var summary = PilotEvaluation.summaries(List.of(row)).getFirst();
    assertThat(summary.get("cases")).isEqualTo(1);
    assertThat(summary.get("passed")).isEqualTo(0L);
  }

  @Test
  void modelFailureMarksUsageUnknownAndDoesNotBecomeManualSuccess() throws Exception {
    var sample = PilotCases.load().get(4);
    var events = new ArrayList<Map<String, Object>>();
    var result =
        new ProbeLoop(
                p -> {
                  throw new IllegalStateException("provider failure");
                },
                new PilotCases.Evidence(sample.orderId(), sample.facts()),
                ProbeLoop.Limits.defaults(),
                events::add)
            .run(sample.orderId(), PilotEvaluation.VERSION, PilotRules.POLICY, Map.of(), true);
    var score =
        PilotEvaluation.score(sample, PilotEvaluation.Group.AGENT, result, events, 1, false);
    assertThat(score.get("usageComplete")).isEqualTo(false);
    assertThat(score.get("passed")).isEqualTo(false);
    assertThat(score.get("manual")).isEqualTo(false);
  }

  @Test
  void batchBudgetStopsBeforeSixtyFirstActualRequest() {
    var requests = new AtomicInteger();
    var budget =
        new PilotEvaluation.BudgetGateway(
            p -> {
              requests.incrementAndGet();
              return response("{}");
            });
    for (int i = 0; i < 60; i++) budget.call(new Prompt("test"));
    assertThatThrownBy(() -> budget.call(new Prompt("test")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(requests.get()).isEqualTo(60);
    assertThat(budget.calls()).isEqualTo(60);
  }
}
