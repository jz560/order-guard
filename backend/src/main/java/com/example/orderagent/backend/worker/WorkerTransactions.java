package com.example.orderagent.backend.worker;

import com.example.orderagent.backend.approval.PlanService;
import com.example.orderagent.backend.task.*;
import com.example.orderagent.backend.task.TaskRecords.*;
import com.example.orderagent.backend.worker.WorkerRecords.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkerTransactions {
  private final WorkerMapper mapper;
  private final TaskMapper tasks;
  private final WorkerSettings settings;
  private final PlanService plans;

  public WorkerTransactions(
      WorkerMapper mapper, TaskMapper tasks, WorkerSettings settings, PlanService plans) {
    this.mapper = mapper;
    this.tasks = tasks;
    this.settings = settings;
    this.plans = plans;
  }

  @Transactional
  public Claim claim(String owner) {
    plans.expireOne();
    var claim = mapper.claim(owner, settings.leaseMillis());
    if (claim != null) audit(claim, AuditType.WORKER_CLAIMED);
    return claim;
  }

  @Transactional
  public boolean complete(Claim claim, Result result) {
    boolean allowed =
        (claim.state() == State.QUEUED && result.nextState() == State.DIAGNOSING)
            || (claim.state() == State.DIAGNOSING && result.nextState() == State.WAITING_APPROVAL);
    if (!allowed || result.delayMillis() < 0 || result.delayMillis() > 60000)
      throw new IllegalArgumentException("非法测试步骤状态转换");
    if (mapper.finish(
            claim,
            result.nextState(),
            null,
            0,
            result.nextState() == State.DIAGNOSING,
            result.delayMillis())
        != 1) return false;
    if (result.nextState() == State.WAITING_APPROVAL) plans.freezeTestPlan(claim.taskId());
    mapper.step(claim, result.type(), "COMPLETED", result.nextState(), result.message());
    audit(claim, AuditType.WORKER_ADVANCED);
    return true;
  }

  @Transactional
  public boolean failed(Claim claim) {
    int failures = claim.workerFailures() + 1;
    boolean exhausted = failures >= settings.maxFailures();
    var next = exhausted ? State.MANUAL : claim.state();
    if (mapper.finish(
            claim,
            next,
            exhausted ? "TEST_STEP_FAILED" : null,
            failures,
            !exhausted,
            settings.retryMillis())
        != 1) return false;
    mapper.step(
        claim,
        "TEST_STEP_ERROR",
        exhausted ? "FAILED" : "RETRY",
        next,
        "测试步骤失败；按持久化次数重试，达到上限后转人工，保留订单限制");
    audit(claim, exhausted ? AuditType.WORKER_FAILED : AuditType.WORKER_RETRY);
    return true;
  }

  private void audit(Claim claim, AuditType type) {
    tasks.audit(claim.taskId(), claim.leaseOwner(), type, UUID.randomUUID());
  }
}
