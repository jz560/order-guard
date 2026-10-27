package com.example.orderagent.backend.execution;

import com.example.orderagent.backend.approval.*;
import com.example.orderagent.backend.approval.PlanRecords.*;
import com.example.orderagent.backend.execution.ExecutionRecords.*;
import com.example.orderagent.backend.task.*;
import com.example.orderagent.backend.task.TaskRecords.*;
import com.example.orderagent.backend.worker.*;
import com.example.orderagent.backend.worker.WorkerRecords.Claim;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ExecutionTransactions {
  private final ExecutionMapper mapper;
  private final PlanMapper plans;
  private final TaskMapper tasks;
  private final WorkerMapper worker;
  private final WorkerSettings settings;
  private final JsonMapper json;

  public ExecutionTransactions(
      ExecutionMapper mapper,
      PlanMapper plans,
      TaskMapper tasks,
      WorkerMapper worker,
      WorkerSettings settings,
      JsonMapper json) {
    this.mapper = mapper;
    this.plans = plans;
    this.tasks = tasks;
    this.worker = worker;
    this.settings = settings;
    this.json = json;
  }

  @Transactional
  public Claim claim(String owner) {
    var c = mapper.claim(owner, settings.leaseMillis());
    if (c != null) tasks.audit(c.taskId(), owner, AuditType.WORKER_CLAIMED, UUID.randomUUID());
    return c;
  }

  public Params params(PlanRow plan, String order) {
    if (plan == null || !plan.paramsHash().equals(PlanService.hash(plan.canonicalParams())))
      throw new IllegalArgumentException("方案摘要无效");
    var params = json.readValue(plan.canonicalParams(), Params.class);
    if (params == null || !params.valid(order) || params.action() != plan.action())
      throw new IllegalArgumentException("方案参数无效");
    return params;
  }

  @Transactional
  public Started start(Claim claim, UUID planId, Facts facts) {
    var task = plans.lockTask(claim.taskId());
    if (task == null || task.state() != State.APPROVED || task.version() != claim.version())
      return null;
    var plan = plans.latest(claim.taskId());
    if (plan == null || !plan.id().equals(planId)) {
      blocked(claim, "PLAN_CHANGED", facts);
      return null;
    }
    var approval = plans.approval(plan.id());
    var p = params(plan, claim.orderId());
    if (approval == null || approval.decision() != Decision.APPROVE) {
      blocked(claim, "APPROVAL_INVALID", facts);
      return null;
    }
    if (!plan.expiresAt().isAfter(plans.now())) return null;
    if (!facts.matches(p, false, 0)) {
      blocked(claim, "BUSINESS_FACTS_CHANGED", facts);
      return null;
    }
    var active = mapper.start(claim, plan.id());
    if (active == null) return null;
    UUID id = UUID.randomUUID();
    mapper.insert(id, claim.taskId(), plan.id(), plan.paramsHash());
    evidence(claim.taskId(), facts, "EXECUTION_PRECHECK");
    tasks.audit(claim.taskId(), claim.leaseOwner(), AuditType.EXECUTION_INTENT, UUID.randomUUID());
    return new Started(active, mapper.execution(claim.taskId()), p);
  }

  @Transactional
  public void blocked(Claim c, String reason, Facts facts) {
    if (c.state() != State.APPROVED) throw new IllegalArgumentException("仅执行前可拦截");
    if (finish(c, State.MANUAL, reason, false, 0, reason, Status.MANUAL, null)) {
      if (facts != null) evidence(c.taskId(), facts, "EXECUTION_PRECHECK");
    }
  }

  @Transactional
  public void precheckUnavailable(Claim c) {
    int failures = c.workerFailures() + 1;
    boolean end = failures >= settings.maxFailures();
    finish(
        c,
        end ? State.MANUAL : State.APPROVED,
        end ? "PRECHECK_UNAVAILABLE" : null,
        !end,
        settings.retryMillis(),
        "PRECHECK_UNAVAILABLE",
        Status.MANUAL,
        null,
        failures);
  }

  @Transactional
  public void received(Claim c, Params p, Operation operation) {
    var ex = mapper.execution(c.taskId());
    if (ex == null) throw new IllegalStateException("缺少执行意图");
    if (operation == null || !operation.matches(ex.operationId(), p)) {
      pending(c, "RESULT_UNCONFIRMED");
      return;
    }
    if (operation.succeeded(p)) {
      finish(
          c,
          State.VERIFYING,
          null,
          true,
          0,
          "VERIFY_BUSINESS_RESULT",
          Status.VERIFYING,
          json.writeValueAsString(operation));
    } else if ("REJECTED".equals(operation.status())
        && operation.completedAt() != null
        && operation.beforeVersion() == operation.afterVersion()) {
      if (finish(
          c,
          State.FINISHED,
          "EXECUTION_REJECTED",
          false,
          0,
          "EXECUTION_REJECTED",
          Status.REJECTED,
          json.writeValueAsString(operation))) plans.release(c.taskId());
    } else pending(c, "RESULT_UNCONFIRMED");
  }

  @Transactional
  public void pending(Claim c, String reason) {
    var ex = mapper.execution(c.taskId());
    if (ex == null) throw new IllegalStateException("缺少执行意图");
    boolean expired = !ex.deadlineAt().isAfter(plans.now());
    State state =
        expired ? State.MANUAL : (c.state() == State.VERIFYING ? State.VERIFYING : State.UNKNOWN);
    long delay =
        switch (Math.min(ex.checks(), 3)) {
          case 0 -> 2000;
          case 1 -> 5000;
          case 2 -> 10000;
          default -> 30000;
        };
    finish(
        c,
        state,
        expired ? "CONFIRMATION_DEADLINE_EXCEEDED" : null,
        !expired,
        delay,
        reason,
        expired ? Status.MANUAL : state == State.VERIFYING ? Status.VERIFYING : Status.UNKNOWN,
        null);
  }

  @Transactional
  public void verified(Claim c, Params p, Operation operation, Facts facts) {
    var ex = mapper.execution(c.taskId());
    if (ex == null
        || operation == null
        || !operation.matches(ex.operationId(), p)
        || !operation.succeeded(p)) {
      pending(c, "INVALID_OPERATION_RESULT");
      return;
    }
    boolean matches = facts.matches(p, true, operation.afterVersion());
    if (finish(
        c,
        matches ? State.FINISHED : State.MANUAL,
        matches ? "REPAIR_VERIFIED" : "VERIFICATION_MISMATCH",
        false,
        0,
        matches ? "REPAIR_VERIFIED" : "VERIFICATION_MISMATCH",
        matches ? Status.SUCCEEDED : Status.MANUAL,
        null)) {
      evidence(c.taskId(), facts, "EXECUTION_VERIFICATION");
      if (matches) plans.release(c.taskId());
    }
  }

  private boolean finish(
      Claim c,
      State state,
      String outcome,
      boolean scheduled,
      long delay,
      String message,
      Status status,
      String result) {
    return finish(c, state, outcome, scheduled, delay, message, status, result, 0);
  }

  private boolean finish(
      Claim c,
      State state,
      String outcome,
      boolean scheduled,
      long delay,
      String message,
      Status status,
      String result,
      int failures) {
    if (worker.finish(c, state, outcome, failures, scheduled, delay) != 1) return false;
    mapper.update(c.taskId(), status, result, message);
    worker.step(c, "CONTROLLED_EXECUTION", "COMPLETED", state, message);
    tasks.audit(c.taskId(), c.leaseOwner(), AuditType.EXECUTION_PROGRESS, UUID.randomUUID());
    return true;
  }

  private void evidence(UUID taskId, Facts facts, String source) {
    long version =
        facts.order() != null && facts.order().version() != null
            ? Math.max(1, facts.order().version())
            : 1;
    mapper.evidence(
        UUID.randomUUID(),
        taskId,
        source,
        version,
        json.writeValueAsString(facts),
        facts.fetchedAt());
  }
}
