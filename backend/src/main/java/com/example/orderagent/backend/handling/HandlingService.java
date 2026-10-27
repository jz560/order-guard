package com.example.orderagent.backend.handling;

import com.example.orderagent.backend.api.ApiException;
import com.example.orderagent.backend.approval.PlanMapper;
import com.example.orderagent.backend.execution.*;
import com.example.orderagent.backend.execution.ExecutionRecords.*;
import com.example.orderagent.backend.handling.HandlingRecords.*;
import com.example.orderagent.backend.security.WorkbenchAccount;
import com.example.orderagent.backend.task.TaskService;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class HandlingService {
  private final HandlingTransactions tx;
  private final ExecutionClient client;
  private final ExecutionMapper executions;
  private final ExecutionTransactions rules;
  private final PlanMapper plans;
  private final TaskService tasks;

  public HandlingService(
      HandlingTransactions tx,
      ExecutionClient client,
      ExecutionMapper executions,
      ExecutionTransactions rules,
      PlanMapper plans,
      TaskService tasks) {
    this.tx = tx;
    this.client = client;
    this.executions = executions;
    this.rules = rules;
    this.plans = plans;
    this.tasks = tasks;
  }

  public HandlingRecords.Record handle(
      WorkbenchAccount account,
      UUID id,
      Action action,
      long version,
      String reason,
      Resolution resolution,
      UUID correlation) {
    if (version < 1 || reason == null || reason.isBlank() || reason.length() > 1000)
      throw new ApiException(400, "INVALID_REQUEST", "需要有效版本及 1—1000 字符原因");
    var prior = tx.replay(account, id, action, version, reason, resolution);
    if (prior != null) return prior;
    Operation operation = null;
    Facts facts = null;
    if (action == Action.RESOLVE && resolution == Resolution.CONFIRM_OPERATION) {
      var task = tasks.detail(account, id);
      if (task.version() != version
          || task.state() != com.example.orderagent.backend.task.TaskRecords.State.MANUAL)
        throw new ApiException(409, "TASK_CHANGED", "任务状态或版本已变化，请刷新");
      var ex = executions.execution(id);
      if (ex == null) throw new ApiException(409, "EXECUTION_NOT_FOUND", "没有待核实执行意图");
      var p = rules.params(plans.plan(ex.planId()), task.orderId());
      // 只读网络调用在事务外；提交时再次锁任务检查版本，禁止人工重发修复。
      try {
        operation = client.query(ex.operationId());
        if (operation.matches(ex.operationId(), p) && operation.succeeded(p))
          facts = client.facts(p);
      } catch (ExecutionClient.Unavailable e) {
        throw new ApiException(409, "RESULT_UNCONFIRMED", "无法确认原操作或业务事实，保留订单限制");
      }
    }
    return tx.apply(
        account, id, action, version, reason, resolution, correlation, operation, facts);
  }
}
