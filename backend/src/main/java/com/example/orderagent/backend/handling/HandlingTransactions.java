package com.example.orderagent.backend.handling;

import com.example.orderagent.backend.api.ApiException;
import com.example.orderagent.backend.approval.PlanMapper;
import com.example.orderagent.backend.execution.*;
import com.example.orderagent.backend.execution.ExecutionRecords.*;
import com.example.orderagent.backend.handling.HandlingRecords.*;
import com.example.orderagent.backend.security.WorkbenchAccount;
import com.example.orderagent.backend.task.*;
import com.example.orderagent.backend.task.TaskRecords.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class HandlingTransactions {
  private final HandlingMapper handling;
  private final PlanMapper plans;
  private final TaskMapper tasks;
  private final ExecutionMapper executions;
  private final ExecutionTransactions executionRules;
  private final JsonMapper json;

  public HandlingTransactions(
      HandlingMapper handling,
      PlanMapper plans,
      TaskMapper tasks,
      ExecutionMapper executions,
      ExecutionTransactions executionRules,
      JsonMapper json) {
    this.handling = handling;
    this.plans = plans;
    this.tasks = tasks;
    this.executions = executions;
    this.executionRules = executionRules;
    this.json = json;
  }

  public void authorize(WorkbenchAccount account, UUID id, Action action) {
    if (!(action == Action.CANCEL ? "OPERATOR" : "APPROVER").equals(account.role()))
      throw new ApiException(403, "FORBIDDEN", "当前角色不能执行此操作");
    var task = tasks.task(id);
    if (task == null || !task.orderId().startsWith(account.orderPrefix()))
      throw new ApiException(404, "TASK_NOT_FOUND", "未找到可访问的任务");
  }

  public HandlingRecords.Record replay(
      WorkbenchAccount account,
      UUID id,
      Action action,
      long version,
      String reason,
      Resolution resolution) {
    authorize(account, id, action);
    var prior = handling.prior(id, action, version);
    if (prior != null
        && (!prior.actor().equals(account.username())
            || !prior.reason().equals(reason.strip())
            || prior.resolution() != resolution))
      throw new ApiException(409, "HANDLING_CONFLICT", "此版本已有不同操作记录");
    return prior;
  }

  @Transactional
  public HandlingRecords.Record apply(
      WorkbenchAccount account,
      UUID id,
      Action action,
      long version,
      String reason,
      Resolution resolution,
      UUID correlation,
      Operation operation,
      Facts facts) {
    authorize(account, id, action);
    var task = plans.lockTask(id);
    var prior = replay(account, id, action, version, reason, resolution);
    if (prior != null) return prior;
    var ex = executions.execution(id);
    if (action == Action.CANCEL && ex != null)
      throw new ApiException(
          409, "EXECUTION_ALREADY_STARTED", "操作已获准开始，无法直接取消；系统将继续确认结果，人工接管后仍须核实原操作");
    if (task.version() != version) throw new ApiException(409, "TASK_CHANGED", "任务版本已变化，请刷新后操作");
    if (task.state() == State.FINISHED)
      throw new ApiException(409, "TASK_STATE_CONFLICT", "任务已经结束");
    String outcome;
    State next;
    UUID evidenceId = null;
    if (action == Action.CANCEL) {
      if (!Set.of(State.QUEUED, State.DIAGNOSING, State.WAITING_APPROVAL, State.APPROVED)
          .contains(task.state()))
        throw new ApiException(409, "TASK_STATE_CONFLICT", "此状态不能取消，请通过人工结案处理");
      next = State.FINISHED;
      outcome = "CANCELLED";
    } else if (action == Action.TAKEOVER) {
      if (task.state() == State.MANUAL)
        throw new ApiException(409, "TASK_STATE_CONFLICT", "任务已转人工，可直接核实结案");
      next = State.MANUAL;
      outcome = ex == null ? "MANUAL_TAKEOVER" : "MANUAL_RESULT_UNCONFIRMED";
      if (ex != null) executions.update(id, Status.MANUAL, null, "MANUAL_TAKEOVER");
    } else {
      if (task.state() != State.MANUAL)
        throw new ApiException(409, "TASK_STATE_CONFLICT", "只有人工任务可以结案");
      next = State.FINISHED;
      if (resolution == Resolution.NO_EXECUTION) {
        if (ex != null) throw new ApiException(409, "EXECUTION_EXISTS", "已有执行意图，不能声明未执行，请核实原操作结果");
        outcome = "MANUAL_CLOSED_WITHOUT_EXECUTION";
      } else if (resolution == Resolution.CONFIRM_OPERATION) {
        if (ex == null) throw new ApiException(409, "EXECUTION_NOT_FOUND", "没有待核实执行意图");
        var p = executionRules.params(plans.plan(ex.planId()), task.orderId());
        if (!ex.paramsHash().equals(plans.plan(ex.planId()).paramsHash())
            || operation == null
            || !operation.matches(ex.operationId(), p))
          throw new ApiException(409, "RESULT_UNCONFIRMED", "原操作结果尚未确认，保留订单限制");
        boolean success = operation.succeeded(p);
        boolean rejected =
            "REJECTED".equals(operation.status())
                && operation.completedAt() != null
                && operation.beforeVersion() == operation.afterVersion();
        if (!success && !rejected)
          throw new ApiException(409, "RESULT_UNCONFIRMED", "操作尚未有确定结果，保留订单限制");
        if (success && (facts == null || !facts.matches(p, true, operation.afterVersion())))
          throw new ApiException(409, "VERIFICATION_MISMATCH", "业务事实尚未验证通过，保留订单限制");
        outcome = success ? "MANUAL_REPAIR_VERIFIED" : "MANUAL_EXECUTION_REJECTED";
        evidenceId = UUID.randomUUID();
        var payload = new ResolutionEvidence(operation, facts);
        executions.evidence(
            evidenceId,
            id,
            "MANUAL_RESOLUTION",
            Math.max(1, operation.afterVersion()),
            json.writeValueAsString(payload),
            Instant.now());
        executions.update(
            id,
            success ? Status.SUCCEEDED : Status.REJECTED,
            json.writeValueAsString(operation),
            outcome);
      } else throw new ApiException(400, "INVALID_REQUEST", "请选择结案方式");
    }
    if (handling.transition(id, version, task.state(), next, outcome) != 1)
      throw new ApiException(409, "TASK_CHANGED", "任务已变化");
    if (next == State.FINISHED) plans.release(id);
    var record =
        new HandlingRecords.Record(
            UUID.randomUUID(),
            id,
            action,
            version,
            account.username(),
            reason.strip(),
            resolution,
            outcome,
            ex == null ? null : ex.operationId(),
            evidenceId,
            null);
    handling.insert(record);
    tasks.audit(
        id,
        account.username(),
        switch (action) {
          case CANCEL -> AuditType.TASK_CANCELLED;
          case TAKEOVER -> AuditType.TASK_TAKEN_OVER;
          case RESOLVE -> AuditType.MANUAL_RESOLVED;
        },
        correlation);
    return handling.prior(id, action, version);
  }

  public record ResolutionEvidence(Operation operation, Facts facts) {}
}
