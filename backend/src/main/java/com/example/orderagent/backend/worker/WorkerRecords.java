package com.example.orderagent.backend.worker;

import com.example.orderagent.backend.task.TaskRecords.State;
import java.time.Instant;
import java.util.UUID;

public final class WorkerRecords {
  private WorkerRecords() {}

  public record Claim(
      UUID taskId,
      String orderId,
      State state,
      long version,
      String leaseOwner,
      long leaseEpoch,
      Instant leaseUntil,
      int workerFailures,
      Instant claimedAt) {}

  public record Result(State nextState, String type, String message, long delayMillis) {}

  public record StepRow(
      long id,
      UUID taskId,
      long seq,
      String type,
      String status,
      State beforeState,
      State afterState,
      String output,
      Instant startedAt,
      Instant finishedAt) {}
}
