package com.example.orderagent.backend.execution;

import com.example.orderagent.backend.approval.PlanMapper;
import com.example.orderagent.backend.execution.ExecutionRecords.*;
import com.example.orderagent.backend.task.TaskRecords.State;
import com.example.orderagent.backend.worker.WorkerRecords.Claim;
import com.example.orderagent.backend.worker.WorkerSettings;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ExecutionWorker {
  private static final Logger LOG = LoggerFactory.getLogger(ExecutionWorker.class);
  private final String owner = "execution-" + UUID.randomUUID();
  private final ExecutionTransactions tx;
  private final ExecutionMapper mapper;
  private final PlanMapper plans;
  private final ExecutionClient client;
  private final WorkerSettings settings;
  private final JsonMapper json;

  public ExecutionWorker(
      ExecutionTransactions tx,
      ExecutionMapper mapper,
      PlanMapper plans,
      ExecutionClient client,
      WorkerSettings settings,
      JsonMapper json) {
    this.tx = tx;
    this.mapper = mapper;
    this.plans = plans;
    this.client = client;
    this.settings = settings;
    this.json = json;
  }

  @Scheduled(
      fixedDelayString = "${agent.worker.poll-ms:500}",
      initialDelayString = "${agent.worker.poll-ms:500}")
  public void tick() {
    if (!settings.enabled() || !client.configured()) return;
    try {
      var claim = tx.claim(owner);
      if (claim == null) return;
      LOG.info(
          "execution claimed taskId={} epoch={} state={}",
          claim.taskId(),
          claim.leaseEpoch(),
          claim.state());
      if (claim.state() == State.APPROVED) begin(claim);
      else resume(claim);
    } catch (RuntimeException e) {
      // 本地写回故障不能推断下游失败。租约到期后从原执行意图继续查询。
      LOG.error(
          "execution worker failed owner={} type={}; recover through persisted intent",
          owner,
          e.getClass().getSimpleName());
    }
  }

  private void begin(Claim c) {
    var plan = plans.latest(c.taskId());
    Params p;
    try {
      p = tx.params(plan, c.orderId());
    } catch (RuntimeException e) {
      tx.blocked(c, "PLAN_INVALID", null);
      return;
    }
    Facts facts;
    try {
      facts = client.facts(p);
    } catch (ExecutionClient.Unavailable e) {
      tx.precheckUnavailable(c);
      return;
    }
    var started = tx.start(c, plan.id(), facts);
    if (started == null) return;
    LOG.info(
        "execution intent taskId={} operationId={}", c.taskId(), started.execution().operationId());
    Operation result;
    try {
      result = client.send(started.execution().operationId(), started.params());
    } catch (ExecutionClient.Unavailable e) {
      tx.pending(started.claim(), "WRITE_RESPONSE_UNKNOWN");
      return;
    }
    tx.received(started.claim(), started.params(), result);
  }

  private void resume(Claim c) {
    var ex = mapper.execution(c.taskId());
    if (ex == null) throw new IllegalStateException("缺少持久化执行意图");
    var plan = plans.plan(ex.planId());
    var p = tx.params(plan, c.orderId());
    if (!ex.paramsHash().equals(plan.paramsHash())) throw new IllegalStateException("执行参数不一致");
    if (c.state() == State.VERIFYING) {
      var operation = ex.result() == null ? null : json.readValue(ex.result(), Operation.class);
      Facts facts;
      try {
        facts = client.facts(p);
      } catch (ExecutionClient.Unavailable e) {
        tx.pending(c, "VERIFICATION_UNAVAILABLE");
        return;
      }
      tx.verified(c, p, operation, facts);
    } else {
      Operation result;
      try {
        result = client.query(ex.operationId());
      } catch (ExecutionClient.Unavailable e) {
        tx.pending(c, "RESULT_UNAVAILABLE_OR_NOT_FOUND");
        return;
      }
      tx.received(c, p, result);
    }
  }
}
