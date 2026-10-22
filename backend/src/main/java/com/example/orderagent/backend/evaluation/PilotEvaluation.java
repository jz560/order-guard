package com.example.orderagent.backend.evaluation;

import com.example.orderagent.backend.diagnosis.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.springframework.ai.chat.prompt.Prompt;

/** T03 实验器：先冻结输入和政策，再运行并保留全部失败。 */
public final class PilotEvaluation {
  public static final String VERSION = "t03-v1";

  public enum Group {
    RULES,
    SINGLE,
    AGENT
  }

  public static final class BudgetGateway implements ModelGateway {
    private final ModelGateway delegate;
    private final AtomicInteger calls = new AtomicInteger();

    public BudgetGateway(ModelGateway delegate) {
      this.delegate = delegate;
    }

    public int calls() {
      return calls.get();
    }

    public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
      int chars =
          prompt.getInstructions().stream()
              .mapToInt(m -> Objects.toString(m.getText(), "").length())
              .sum();
      if (chars > 16000) throw new IllegalStateException("INPUT_LIMIT");
      if (calls.getAndUpdate(n -> n < 60 ? n + 1 : n) >= 60)
        throw new IllegalStateException("BATCH_REQUEST_LIMIT");
      return delegate.call(prompt);
    }
  }

  public static Map<String, Object> score(
      PilotCases.Case sample,
      Group group,
      ProbeLoop.Result result,
      List<Map<String, Object>> events,
      long elapsedMs,
      boolean live) {
    var diagnosis = result.diagnosis();
    String raw = "UNPARSEABLE";
    long input = 0, output = 0, responses = 0;
    boolean known = true;
    Set<String> retrieved = new TreeSet<>();
    long bytes = 0;
    if (group != Group.AGENT) {
      var initial = new PilotCases.Evidence(sample.orderId(), sample.facts()).all();
      retrieved.addAll(initial.keySet());
      bytes =
          initial.values().stream().mapToInt(v -> v.getBytes(StandardCharsets.UTF_8).length).sum();
    }
    for (var event : events) {
      if ("TOOL_RESULT".equals(event.get("type"))) {
        var body = PilotCases.JSON.readTree((String) event.get("result"));
        retrieved.add(body.path("evidenceId").asText());
        bytes += ((String) event.get("result")).getBytes(StandardCharsets.UTF_8).length;
      }
      if ("MODEL_RESPONSE".equals(event.get("type"))) {
        responses++;
        try {
          input += Long.parseLong(event.get("inputTokens").toString());
          output += Long.parseLong(event.get("outputTokens").toString());
        } catch (NumberFormatException failure) {
          known = false;
        }
        try {
          raw =
              PilotCases.JSON
                  .readTree(Objects.toString(event.get("text"), ""))
                  .path("recommendation")
                  .asText("UNPARSEABLE");
        } catch (tools.jackson.core.JacksonException | NullPointerException failure) {
          raw = "UNPARSEABLE";
        }
      }
    }
    if (group == Group.RULES) raw = diagnosis.path("recommendation").asText();
    String actual =
        diagnosis == null ? "INVALID_OR_STOPPED" : diagnosis.path("recommendation").asText();
    Set<String> cited = new HashSet<>();
    if (diagnosis != null) diagnosis.path("evidenceIds").forEach(ref -> cited.add(ref.asText()));
    boolean valid = "COMPLETED".equals(result.status());
    boolean match = valid && sample.expected().name().equals(actual);
    boolean covered = valid && cited.containsAll(sample.requiredEvidence());
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("caseId", sample.id());
    row.put("split", sample.split());
    row.put("group", group);
    row.put("liveModel", live && group != Group.RULES);
    row.put("expected", sample.expected());
    row.put("actual", actual);
    row.put("rawRecommendation", raw);
    row.put("status", result.status());
    row.put("stopReason", result.reason());
    row.put("formatValid", valid);
    row.put("labelMatches", match);
    row.put("requiredEvidenceCovered", covered);
    row.put("passed", match && covered);
    row.put(
        "unsafeRepairSuggestion",
        "RECOMMEND_RECHECK".equals(raw)
            && sample.expected() != PilotCases.Recommendation.RECOMMEND_RECHECK);
    row.put("manual", "MANUAL".equals(actual));
    row.put("appropriateManual", "MANUAL".equals(actual) && match && covered);
    row.put(
        "unnecessaryManual",
        "MANUAL".equals(actual) && sample.expected() != PilotCases.Recommendation.MANUAL);
    row.put("elapsedMs", elapsedMs);
    row.put("modelCalls", result.modelCalls());
    row.put("toolCalls", result.toolCalls());
    row.put("evidenceReads", group == Group.AGENT ? result.toolCalls() : 4);
    row.put("retrievedEvidence", retrieved);
    row.put("evidenceBytes", bytes);
    row.put("knownInputTokens", input);
    row.put("knownOutputTokens", output);
    row.put("usageComplete", known && responses == result.modelCalls());
    row.put("diagnosis", diagnosis);
    return row;
  }

  public static List<Map<String, Object>> summaries(List<Map<String, Object>> rows) {
    List<Map<String, Object>> summaries = new ArrayList<>();
    for (var group : Group.values())
      for (var split : List.of("ALL", "DEVELOPMENT", "PILOT_HOLDOUT")) {
        var selected =
            rows.stream()
                .filter(
                    r ->
                        group.equals(r.get("group"))
                            && (split.equals("ALL") || split.equals(r.get("split").toString())))
                .toList();
        if (selected.isEmpty()) continue;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("group", group);
        item.put("split", split);
        item.put("cases", selected.size());
        for (String key :
            List.of(
                "passed",
                "labelMatches",
                "requiredEvidenceCovered",
                "formatValid",
                "unsafeRepairSuggestion",
                "manual",
                "appropriateManual",
                "unnecessaryManual"))
          item.put(key, selected.stream().filter(r -> Boolean.TRUE.equals(r.get(key))).count());
        for (String key :
            List.of(
                "elapsedMs",
                "modelCalls",
                "toolCalls",
                "evidenceReads",
                "evidenceBytes",
                "knownInputTokens",
                "knownOutputTokens"))
          item.put(key, selected.stream().mapToLong(r -> ((Number) r.get(key)).longValue()).sum());
        item.put(
            "unknownUsageCases",
            selected.stream().filter(r -> !Boolean.TRUE.equals(r.get("usageComplete"))).count());
        summaries.add(item);
      }
    return summaries;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1 || !Set.of("offline", "deepseek").contains(args[0]))
      throw new IllegalArgumentException("用法：offline|deepseek");
    boolean live = "deepseek".equals(args[0]);
    if (live && !"true".equals(System.getenv("T03_ALLOW_LIVE")))
      throw new IllegalArgumentException("真实批量实验需要 T03_ALLOW_LIVE=true");
    BudgetGateway gateway =
        live
            ? new BudgetGateway(new DeepSeekModelGateway(System.getenv("DEEPSEEK_API_KEY")))
            : null;
    var directory = Path.of(".cache", "t03-runs", UUID.randomUUID().toString());
    Files.createDirectories(directory);
    var cases = PilotCases.load();
    byte[] dataset;
    try (var stream = PilotCases.class.getResourceAsStream("/evaluation/t03-cases.json")) {
      dataset = Objects.requireNonNull(stream).readAllBytes();
    }
    Files.write(directory.resolve("cases.json"), dataset, StandardOpenOption.CREATE_NEW);
    Files.writeString(
        directory.resolve("policy.txt"), PilotRules.POLICY, StandardOpenOption.CREATE_NEW);
    Files.writeString(
        directory.resolve("manifest.json"),
        PilotCases.JSON.writeValueAsString(
            Map.of(
                "version",
                VERSION,
                "createdAt",
                Instant.now().toString(),
                "mode",
                args[0],
                "model",
                live ? "deepseek-flash" : "NONE",
                "datasetSHA256",
                hash(dataset),
                "policySHA256",
                hash(PilotRules.POLICY.getBytes(StandardCharsets.UTF_8)),
                "requestLimit",
                60,
                "roundsPerAgent",
                4,
                "maxOutputTokens",
                1000,
                "automaticRetries",
                0)),
        StandardOpenOption.CREATE_NEW);
    List<Map<String, Object>> rows = new ArrayList<>();
    long deadline = System.nanoTime() + Duration.ofMinutes(30).toNanos();
    for (var sample : cases)
      for (var group : Group.values()) {
        if (!live && group != Group.RULES) continue; // 离线只跑规则，不能以替身结果充当 AI 效果。
        if (System.nanoTime() >= deadline) throw new IllegalStateException("批次总时限达到，已有结果已保留");
        long started = System.nanoTime();
        List<Map<String, Object>> events = new ArrayList<>();
        ProbeLoop.Result result;
        String tracePath = "NONE";
        if (group == Group.RULES) {
          result =
              new ProbeLoop.Result(
                  "COMPLETED", "RULES", 0, 0, PilotRules.diagnose(sample.facts()).json());
        } else {
          var trace = new ProbeTrace(directory.resolve("traces"));
          tracePath = directory.relativize(trace.file()).toString();
          Consumer<Map<String, Object>> audit =
              e -> {
                trace.accept(e);
                events.add(e);
              };
          var source = new PilotCases.Evidence(sample.orderId(), sample.facts());
          var limits =
              group == Group.SINGLE
                  ? new ProbeLoop.Limits(1, 12, 2, Duration.ofSeconds(30), Duration.ofSeconds(35))
                  : ProbeLoop.Limits.defaults();
          result =
              new ProbeLoop(gateway, source, limits, audit)
                  .run(
                      sample.orderId(),
                      VERSION,
                      PilotRules.POLICY,
                      group == Group.SINGLE ? source.all() : Map.of(),
                      group == Group.AGENT);
        }
        var row =
            score(
                sample,
                group,
                result,
                events,
                Duration.ofNanos(System.nanoTime() - started).toMillis(),
                live);
        row.put("trace", tracePath);
        rows.add(row);
        Files.writeString(
            directory.resolve("results.jsonl"),
            PilotCases.JSON.writeValueAsString(row) + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND);
        Files.writeString(
            directory.resolve("summary.json"),
            PilotCases.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summaries(rows)));
        System.out.println(
            sample.id()
                + " "
                + group
                + " actual="
                + row.get("actual")
                + " passed="
                + row.get("passed")
                + " modelCalls="
                + result.modelCalls());
        if (Thread.currentThread().isInterrupted()
            || "MODEL_TIMEOUT_USAGE_UNKNOWN".equals(result.reason()))
          throw new IllegalStateException("模型超时或中断，停止整批以避免未完成请求重叠；已有记录保留");
      }
    System.out.println(
        "mode="
            + args[0]
            + ", actualProviderRequests="
            + (gateway == null ? 0 : gateway.calls())
            + ", directory="
            + directory.toAbsolutePath());
  }

  static String hash(byte[] value) throws java.security.NoSuchAlgorithmException {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
  }
}
