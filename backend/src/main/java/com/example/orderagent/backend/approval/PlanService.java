package com.example.orderagent.backend.approval;

import com.example.orderagent.backend.api.ApiException;
import com.example.orderagent.backend.approval.PlanRecords.*;
import com.example.orderagent.backend.security.WorkbenchAccount;
import com.example.orderagent.backend.task.*;
import com.example.orderagent.backend.task.TaskRecords.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PlanService {
  private final PlanMapper mapper;
  private final TaskMapper tasks;
  private final TaskService taskService;
  private final JsonMapper json;

  public PlanService(
      PlanMapper mapper, TaskMapper tasks, TaskService taskService, JsonMapper json) {
    this.mapper = mapper;
    this.tasks = tasks;
    this.taskService = taskService;
    this.json = json;
  }

  // 与 Worker 的租约条件更新共用事务；旧 Worker 不会留下孤立方案。
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public void freezeTestPlan(UUID taskId) {
    var task = tasks.task(taskId);
    var evidence =
        tasks.evidence(taskId).stream()
            .filter(e -> e.source().equals("SIMULATOR_ORDER"))
            .reduce((a, b) -> b)
            .orElseThrow(() -> new IllegalStateException("缺少订单快照"));
    var order = json.readValue(evidence.payload(), SimulatorOrderClient.Order.class);
    if (!task.orderId().equals(order.id())
        || order.paymentId() == null
        || !order.id().matches("[A-Za-z0-9_-]{1,100}")
        || !order.paymentId().matches("[A-Za-z0-9_-]{1,100}")
        || order.amountMinor() == null
        || order.amountMinor() < 1
        || !"CNY".equals(order.currency())
        || order.version() == null
        || order.version() < 1
        || order.version() != evidence.businessVersion())
      throw new IllegalStateException("订单快照不一致");
    // 固定 ASCII 字段、顺序及整数表示，摘要同时绑定动作，避免 JSON 字段顺序歧义。
    var action = Action.VERIFY_PAYMENT_AND_ADVANCE_ORDER;
    String params =
        "{\"action\":\"%s\",\"amountMinor\":%d,\"currency\":\"CNY\",\"expectedVersion\":%d,\"orderId\":\"%s\",\"paymentId\":\"%s\"}"
            .formatted(action, order.amountMinor(), order.version(), order.id(), order.paymentId());
    mapper.insert(UUID.randomUUID(), taskId, action, params, hash(params), evidence.id());
    tasks.audit(taskId, "plan-stub", AuditType.PLAN_CREATED, UUID.randomUUID());
  }

  public static String hash(String params) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(params.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public PlanRow current(WorkbenchAccount account, UUID taskId) {
    taskService.detail(account, taskId);
    var plan = mapper.latest(taskId);
    if (plan == null) throw new ApiException(404, "PLAN_NOT_FOUND", "任务尚未生成方案");
    return plan;
  }

  public PlanRow accessible(WorkbenchAccount account, UUID id) {
    var plan = mapper.plan(id);
    if (plan == null) throw new ApiException(404, "PLAN_NOT_FOUND", "未找到可访问的方案");
    taskService.detail(account, plan.taskId());
    return plan;
  }

  @Transactional
  public PlanRow decide(
      WorkbenchAccount account,
      UUID id,
      long version,
      String paramsHash,
      Decision decision,
      String reason,
      UUID correlation) {
    if (!account.role().equals("APPROVER")) throw new ApiException(403, "FORBIDDEN", "仅审批员可以决定方案");
    var plan = accessible(account, id);
    var task = mapper.lockTask(plan.taskId());
    if (version != plan.version()
        || !plan.paramsHash().equals(paramsHash)
        || !plan.paramsHash().equals(hash(plan.canonicalParams()))
        || !mapper.latest(task.id()).id().equals(id))
      throw new ApiException(409, "PLAN_CHANGED", "方案版本或参数已变化，请刷新方案");
    String normalized = reason == null ? null : reason.strip();
    if (decision == null
        || (decision == Decision.REJECT && (normalized == null || normalized.isBlank()))
        || (normalized != null && normalized.length() > 1000))
      throw new ApiException(400, "INVALID_DECISION", "拒绝须填写原因，原因最多 1000 字符");
    var prior = mapper.approval(id);
    if (prior != null) {
      if (prior.decision() != decision
          || !prior.approverId().equals(account.username())
          || !Objects.equals(prior.reason(), normalized))
        throw new ApiException(409, "DECISION_CONFLICT", "此方案已有最终审批决定");
      return plan; // 返回既有决定及当前状态，不重新授权或延长有效期。
    }
    if (!plan.expiresAt().isAfter(mapper.now()))
      throw new ApiException(409, "PLAN_EXPIRED", "方案已过期");
    if (task.state() != State.WAITING_APPROVAL)
      throw new ApiException(409, "TASK_STATE_CONFLICT", "任务不在等待审批状态");
    boolean approve = decision == Decision.APPROVE;
    if (mapper.transition(
            task.id(),
            task.version(),
            id,
            approve ? State.APPROVED : State.FINISHED,
            approve ? null : "PLAN_REJECTED")
        != 1) throw new ApiException(409, "PLAN_EXPIRED", "方案已过期，请刷新任务");
    mapper.decide(UUID.randomUUID(), id, decision, account.username(), normalized);
    if (!approve) mapper.release(task.id());
    tasks.audit(
        task.id(),
        account.username(),
        approve ? AuditType.PLAN_APPROVED : AuditType.PLAN_REJECTED,
        correlation);
    return plan;
  }

  @Transactional
  public void expireOne() {
    var task = mapper.lockExpired();
    if (task == null) return;
    if (mapper.expire(task.id(), task.version()) == 1) {
      mapper.release(task.id());
      tasks.audit(task.id(), "plan-expiry", AuditType.PLAN_EXPIRED, UUID.randomUUID());
    }
  }
}
