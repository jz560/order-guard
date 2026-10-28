package com.example.orderagent.backend.worker;

import com.example.orderagent.backend.worker.WorkerRecords.*;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class TaskWorker {
  private static final Logger LOG = LoggerFactory.getLogger(TaskWorker.class);
  private final String owner = "worker-" + UUID.randomUUID();
  private final WorkerTransactions transactions;
  private final TestTaskStep step;
  private final WorkerSettings settings;
  private final com.example.orderagent.backend.diagnosis.TaskDiagnosis diagnosis;

  public TaskWorker(
      WorkerTransactions transactions,
      TestTaskStep step,
      WorkerSettings settings,
      com.example.orderagent.backend.diagnosis.TaskDiagnosis diagnosis) {
    this.transactions = transactions;
    this.step = step;
    this.settings = settings;
    this.diagnosis = diagnosis;
  }

  @Scheduled(
      fixedDelayString = "${agent.worker.poll-ms:500}",
      initialDelayString = "${agent.worker.poll-ms:500}")
  public void tick() {
    if (!settings.enabled()) return;
    try {
      var claim = transactions.claim(owner);
      if (claim == null) return;
      if (diagnosis.enabled()) {
        diagnosis.run(claim);
        return;
      }
      Result result;
      // 处理步骤位于认领事务之外；写回失败时保留租约，过期后由扫描恢复。
      try {
        result = step.run(claim);
      } catch (RuntimeException ex) {
        boolean accepted = transactions.failed(claim);
        LOG.warn(
            "worker step failed taskId={} epoch={} accepted={} type={}",
            claim.taskId(),
            claim.leaseEpoch(),
            accepted,
            ex.getClass().getSimpleName());
        return;
      }
      boolean accepted = transactions.complete(claim, result);
      LOG.info(
          "worker step taskId={} epoch={} accepted={} nextState={}",
          claim.taskId(),
          claim.leaseEpoch(),
          accepted,
          result.nextState());
    } catch (RuntimeException ex) {
      LOG.error(
          "worker tick failed owner={} type={}; database lease will govern recovery",
          owner,
          ex.getClass().getSimpleName());
    }
  }
}
