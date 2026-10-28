package com.example.orderagent.backend.diagnosis;

import com.example.orderagent.backend.approval.*;
import com.example.orderagent.backend.execution.ExecutionMapper;
import com.example.orderagent.backend.task.*;
import com.example.orderagent.backend.task.TaskRecords.*;
import com.example.orderagent.backend.worker.*;
import com.example.orderagent.backend.worker.WorkerRecords.Claim;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class DiagnosisTransactions {
  private final DiagnosisJournal journal;
  private final PlanMapper plans;
  private final WorkerMapper worker;
  private final TaskMapper tasks;
  private final ExecutionMapper evidence;
  private final DiagnosisSettings settings;

  public DiagnosisTransactions(
      DiagnosisJournal journal,
      PlanMapper plans,
      WorkerMapper worker,
      TaskMapper tasks,
      ExecutionMapper evidence,
      DiagnosisSettings settings) {
    this.journal = journal;
    this.plans = plans;
    this.worker = worker;
    this.tasks = tasks;
    this.evidence = evidence;
    this.settings = settings;
  }

  private boolean lock(Claim c) {
    plans.lockTask(c.taskId());
    return journal.owned(c) == 1;
  }

  private void event(Claim c, String type, Object data) {
    journal.event(c, type, TaskEvidenceTools.JSON.writeValueAsString(data));
  }

  @Transactional
  public boolean start(Claim c) {
    if (!lock(c)) return false;
    journal.init(c.taskId(), settings.totalMs);
    return finish(c, State.DIAGNOSING, "DIAGNOSIS_STARTED", true, 0);
  }

  @Transactional
  public boolean reserveModel(Claim c) {
    if (!lock(c)) return false;
    journal.init(c.taskId(), settings.totalMs);
    var r = journal.run(c.taskId());
    if (!r.deadlineAt().isAfter(plans.now()) || r.rounds() >= settings.rounds) {
      stopLocked(c, "DIAGNOSIS_BUDGET");
      return false;
    }
    journal.round(c.taskId());
    event(
        c,
        "MODEL_INTENT",
        Map.of(
            "round",
            r.rounds() + 1,
            "promptVersion",
            DiagnosisPolicy.VERSION,
            "model",
            settings.stub ? "task-stub" : "deepseek-flash",
            "usage",
            "UNKNOWN_UNTIL_RESPONSE"));
    return true;
  }

  @Transactional
  public boolean response(Claim c, Object response) {
    if (!lock(c)) return false;
    event(c, "MODEL_RESPONSE", response);
    return true;
  }

  @Transactional
  public boolean reserveTool(Claim c, String name, String args) {
    if (!lock(c)) return false;
    var r = journal.run(c.taskId());
    if (!r.deadlineAt().isAfter(plans.now())
        || r.tools() >= settings.tools
        || journal.repeats(c.taskId(), name, r.reviews()) >= settings.repeats) {
      stopLocked(c, "TOOL_BUDGET_OR_LOOP");
      return false;
    }
    journal.tool(c.taskId());
    event(c, "TOOL_INTENT", Map.of("name", name, "arguments", args, "review", r.reviews()));
    return true;
  }

  @Transactional
  public boolean saveEvidence(Claim c, String name, JsonNode data) {
    if (!lock(c)) return false;
    var id = UUID.randomUUID();
    int review = journal.run(c.taskId()).reviews();
    evidence.evidence(
        id,
        c.taskId(),
        "DIAG_" + name,
        Math.max(1, data.path("version").asLong(1)),
        TaskEvidenceTools.JSON.writeValueAsString(Map.of("review", review, "data", data)),
        plans.now());
    event(c, "TOOL_RESULT", Map.of("name", name, "evidenceId", id, "review", review));
    return true;
  }

  @Transactional
  public boolean next(Claim c) {
    if (!lock(c)) return false;
    return finish(c, State.DIAGNOSING, "DIAGNOSIS_CONTINUE", true, 0);
  }

  @Transactional
  public void stop(Claim c, String reason) {
    if (lock(c)) stopLocked(c, reason);
  }

  private void stopLocked(Claim c, String reason) {
    var result = Map.of("recommendation", "MANUAL", "reason", reason, "evidenceIds", List.of());
    journal.result(c.taskId(), TaskEvidenceTools.JSON.writeValueAsString(result));
    event(c, "STOPPED", result);
    finish(c, State.MANUAL, reason, false, 0);
  }

  @Transactional
  public boolean conclude(
      Claim c,
      JsonNode result,
      DiagnosisPolicy.Verdict policy,
      Map<String, JsonNode> facts,
      Map<String, UUID> refs) {
    if (!lock(c)) return false;
    var r = journal.run(c.taskId());
    if (!r.deadlineAt().isAfter(plans.now())) {
      stopLocked(c, "DIAGNOSIS_BUDGET");
      return false;
    }
    DiagnosisPolicy.validate(result.toString(), refs, policy);
    String recommendation = result.path("recommendation").asText();
    // 保留模型原始公开输出；对用户呈现的结论由规则生成，避免把自由文本当作事实。
    var accepted =
        Map.of(
            "recommendation",
            recommendation,
            "reason",
            recommendation.equals("MANUAL") ? "模型建议人工核实；" + policy.reason() : policy.reason(),
            "evidenceIds",
            result.path("evidenceIds"),
            "promptVersion",
            DiagnosisPolicy.VERSION);
    journal.result(c.taskId(), TaskEvidenceTools.JSON.writeValueAsString(accepted));
    event(c, "CONCLUSION", accepted);
    if (recommendation.equals("WAIT")) {
      if (r.reviews() >= settings.reviews) {
        stopLocked(c, "WAIT_REVIEW_LIMIT");
        return true;
      }
      journal.review(c.taskId());
      return finish(c, State.DIAGNOSING, "WAIT_RECHECK", true, settings.reviewMs);
    }
    State next =
        switch (recommendation) {
          case "NO_ACTION" -> State.FINISHED;
          case "RECOMMEND_RECHECK" -> State.WAITING_APPROVAL;
          default -> State.MANUAL;
        };
    if (recommendation.equals("RECOMMEND_RECHECK")) {
      var o = facts.get("query_order");
      if (!c.orderId().equals(o.path("id").asText())
          || !o.path("paymentId").asText().matches("[A-Za-z0-9_-]{1,100}"))
        throw new IllegalArgumentException("INVALID_PLAN");
      String params =
          "{\"action\":\"VERIFY_PAYMENT_AND_ADVANCE_ORDER\",\"amountMinor\":%d,\"currency\":\"CNY\",\"expectedVersion\":%d,\"orderId\":\"%s\",\"paymentId\":\"%s\"}"
              .formatted(
                  o.path("amountMinor").asLong(),
                  o.path("version").asLong(),
                  c.orderId(),
                  o.path("paymentId").asText());
      journal.plan(
          UUID.randomUUID(), c.taskId(), params, PlanService.hash(params), refs.get("query_order"));
      tasks.audit(c.taskId(), "diagnosis-policy", AuditType.PLAN_CREATED, UUID.randomUUID());
    }
    boolean done = finish(c, next, recommendation, false, 0);
    if (next == State.FINISHED) plans.release(c.taskId());
    return done;
  }

  private boolean finish(Claim c, State state, String outcome, boolean scheduled, long delay) {
    if (worker.finish(c, state, outcome, 0, scheduled, delay) != 1)
      throw new IllegalStateException("诊断租约失效");
    worker.step(c, "DIAGNOSIS", state == State.MANUAL ? "FAILED" : "COMPLETED", state, outcome);
    tasks.audit(c.taskId(), c.leaseOwner(), AuditType.WORKER_ADVANCED, UUID.randomUUID());
    return true;
  }
}
