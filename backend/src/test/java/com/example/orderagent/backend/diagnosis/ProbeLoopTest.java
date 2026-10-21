package com.example.orderagent.backend.diagnosis;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class ProbeLoopTest {
  private final List<Map<String, Object>> events = new ArrayList<>();

  private ProbeLoop loop(ModelGateway model, ProbeLoop.Limits limits) {
    return new ProbeLoop(model, new ProbeTools(), limits, events::add);
  }

  static ChatResponse text(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  static ChatResponse calls(String... names) {
    return new ChatResponse(
        List.of(
            new Generation(
                AssistantMessage.builder()
                    .content("")
                    .toolCalls(
                        Arrays.stream(names)
                            .map(
                                name ->
                                    new AssistantMessage.ToolCall(
                                        UUID.randomUUID().toString(),
                                        "function",
                                        name,
                                        "{\"orderId\":\"T02-ORDER-001\"}"))
                            .toList())
                    .build())));
  }

  @Test
  void recordsEveryRoundAndToolAndAcceptsCitedResult() {
    var result = loop(T02Probe.scripted(), ProbeLoop.Limits.defaults()).run(ProbeTools.ORDER_ID);
    assertThat(result.status()).isEqualTo("COMPLETED");
    assertThat(result.modelCalls()).isEqualTo(2);
    assertThat(result.toolCalls()).isEqualTo(4);
    assertThat(events.stream().filter(e -> "MODEL_INTENT".equals(e.get("type"))).count())
        .isEqualTo(2);
    assertThat(events.stream().filter(e -> "MODEL_RESPONSE".equals(e.get("type"))).count())
        .isEqualTo(2);
    assertThat(events.stream().filter(e -> "TOOL_RESULT".equals(e.get("type"))).count())
        .isEqualTo(4);
  }

  @Test
  void roundLimitStopsBeforeAnotherRequest() {
    var counter = new AtomicInteger();
    var result =
        loop(
                p -> {
                  counter.incrementAndGet();
                  return calls("query_order");
                },
                new ProbeLoop.Limits(1, 12, 2, Duration.ofSeconds(1), Duration.ofSeconds(5)))
            .run(ProbeTools.ORDER_ID);
    assertThat(result.reason()).isEqualTo("ROUND_LIMIT");
    assertThat(counter.get()).isEqualTo(1);
  }

  @Test
  void toolLimitAppliesWithinParallelToolBatch() {
    var result =
        loop(
                p -> calls("query_order", "query_payment"),
                new ProbeLoop.Limits(4, 1, 2, Duration.ofSeconds(1), Duration.ofSeconds(5)))
            .run(ProbeTools.ORDER_ID);
    assertThat(result.reason()).isEqualTo("TOOL_LIMIT");
    assertThat(events.stream().filter(e -> "TOOL_RESULT".equals(e.get("type"))).count())
        .isEqualTo(1);
  }

  @Test
  void repeatedCallsStopAndNoWriteToolExists() {
    assertThat(
            loop(p -> calls("query_order"), ProbeLoop.Limits.defaults())
                .run(ProbeTools.ORDER_ID)
                .reason())
        .isEqualTo("REPEATED_TOOL");
    assertThat(
            loop(p -> calls("repair_order"), ProbeLoop.Limits.defaults())
                .run(ProbeTools.ORDER_ID)
                .reason())
        .isEqualTo("INVALID_TOOL_ARGUMENTS");
  }

  @Test
  void rejectsOtherOrdersExtraFieldsAndMalformedArguments() {
    var tools = new ProbeTools();
    for (var args :
        List.of(
            "{}",
            "null",
            "[]",
            "garbage",
            "",
            "{\"orderId\":\"T02-ORDER-001\"} {}",
            "{\"orderId\":\"OTHER\",\"orderId\":\"T02-ORDER-001\"}",
            "{\"orderId\":123}",
            "{\"orderId\":\"OTHER\"}",
            "{\"orderId\":\"T02-ORDER-001\",\"approved\":true}")) {
      assertThatThrownBy(() -> tools.execute("query_order", args, ProbeTools.ORDER_ID))
          .isInstanceOf(RuntimeException.class);
    }
    assertThatThrownBy(() -> tools.definitions().getFirst().call("{}"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> loop(T02Probe.scripted(), ProbeLoop.Limits.defaults()).run("OTHER"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsInventedEvidenceUnknownActionsAndIncompleteRepairEvidence() {
    for (var body :
        List.of(
            "not-json",
            "",
            "{\"recommendation\":\"MANUAL\",\"reason\":\"x\",\"evidenceIds\":[]} {}",
            "null",
            "{\"recommendation\":\"EXECUTE\",\"reason\":\"x\",\"evidenceIds\":[]}",
            "{\"recommendation\":\"MANUAL\",\"reason\":\"x\",\"evidenceIds\":[\"fake\"]}",
            "{\"recommendation\":\"RECOMMEND_RECHECK\",\"reason\":\"x\",\"evidenceIds\":[]}")) {
      assertThat(
              loop(p -> text(body), ProbeLoop.Limits.defaults()).run(ProbeTools.ORDER_ID).reason())
          .isEqualTo("INVALID_DIAGNOSIS");
    }
  }

  @Test
  void timeoutStopsWithoutWaitingForUnresponsiveModelAndRecordsUnknownUsage() {
    var result =
        loop(
                p -> {
                  try {
                    Thread.sleep(5000);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return text("{}");
                },
                new ProbeLoop.Limits(4, 12, 2, Duration.ofMillis(20), Duration.ofSeconds(1)))
            .run(ProbeTools.ORDER_ID);
    assertThat(result.reason()).isEqualTo("MODEL_TIMEOUT_USAGE_UNKNOWN");
    assertThat(result.modelCalls()).isEqualTo(1);
  }

  @Test
  void providerErrorsAreSanitizedAndAuditFailurePreventsRequest() {
    var result =
        loop(
                p -> {
                  throw new IllegalStateException("secret-key-provider-body");
                },
                ProbeLoop.Limits.defaults())
            .run(ProbeTools.ORDER_ID);
    assertThat(result.reason()).isEqualTo("MODEL_ERROR");
    assertThat(events.toString()).doesNotContain("secret-key");
    var counter = new AtomicInteger();
    assertThatThrownBy(
            () ->
                new ProbeLoop(
                        p -> {
                          counter.incrementAndGet();
                          return text("{}");
                        },
                        new ProbeTools(),
                        ProbeLoop.Limits.defaults(),
                        e -> {
                          throw new IllegalStateException("disk full");
                        })
                    .run(ProbeTools.ORDER_ID))
        .isInstanceOf(IllegalStateException.class);
    assertThat(counter.get()).isZero();
  }

  @Test
  void tracePersistsEventsAsIndependentJsonLines(@TempDir Path directory) throws Exception {
    var trace = new ProbeTrace(directory);
    new ProbeLoop(T02Probe.scripted(), new ProbeTools(), ProbeLoop.Limits.defaults(), trace)
        .run(ProbeTools.ORDER_ID);
    var lines = Files.readAllLines(trace.file());
    assertThat(lines).hasSize(14);
    assertThat(lines.getFirst()).contains("START", "runId", "t02-v1");
    assertThat(lines.getLast()).contains("COMPLETED");
  }

  @Test
  void totalDeadlineStopsBeforeModelCall() {
    var counter = new AtomicInteger();
    var result =
        new ProbeLoop(
                p -> {
                  counter.incrementAndGet();
                  return text("{}");
                },
                new ProbeTools(),
                new ProbeLoop.Limits(4, 12, 2, Duration.ofSeconds(1), Duration.ofMillis(1)),
                event -> {
                  if ("START".equals(event.get("type"))) {
                    try {
                      Thread.sleep(10);
                    } catch (InterruptedException failure) {
                      Thread.currentThread().interrupt();
                    }
                  }
                  events.add(event);
                })
            .run(ProbeTools.ORDER_ID);
    assertThat(result.reason()).isEqualTo("TOTAL_TIMEOUT");
    assertThat(counter.get()).isZero();
  }

  @Test
  void oversizedModelOutputStopsWithoutTools() {
    var result =
        loop(p -> text("x".repeat(16001)), ProbeLoop.Limits.defaults()).run(ProbeTools.ORDER_ID);
    assertThat(result.reason()).isEqualTo("OUTPUT_LIMIT");
    assertThat(result.toolCalls()).isZero();
  }

  @Test
  void invalidModelToolArgumentsAreRecordedAndNeverReturnEvidence() {
    var response =
        new ChatResponse(
            List.of(
                new Generation(
                    AssistantMessage.builder()
                        .content("")
                        .toolCalls(
                            List.of(
                                new AssistantMessage.ToolCall(
                                    "bad-1", "function", "query_order", "{\"orderId\":\"OTHER\"}")))
                        .build())));
    var result = loop(p -> response, ProbeLoop.Limits.defaults()).run(ProbeTools.ORDER_ID);
    assertThat(result.reason()).isEqualTo("INVALID_TOOL_ARGUMENTS");
    assertThat(events.stream().map(e -> e.get("type")))
        .contains("TOOL_INTENT", "STOPPED")
        .doesNotContain("TOOL_RESULT");
    assertThat(result.modelCalls()).isEqualTo(1);
  }
}
