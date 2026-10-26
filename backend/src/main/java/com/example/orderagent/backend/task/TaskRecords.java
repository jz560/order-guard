package com.example.orderagent.backend.task;

import java.time.Instant;
import java.util.UUID;

public final class TaskRecords {
  private TaskRecords() {}

  public enum State {
    QUEUED,
    DIAGNOSING,
    WAITING_APPROVAL,
    APPROVED,
    EXECUTING,
    UNKNOWN,
    VERIFYING,
    MANUAL,
    FINISHED
  }

  public enum AuditType {
    TASK_CREATED,
    TASK_REUSED,
    WORKER_CLAIMED,
    WORKER_ADVANCED,
    WORKER_RETRY,
    WORKER_FAILED
  }

  public record TaskRow(
      UUID id,
      String orderId,
      State state,
      String outcome,
      long version,
      String createdBy,
      Instant createdAt,
      Instant updatedAt,
      Instant nextRunAt) {}

  public record Guard(String orderId, UUID taskId, String reason) {}

  public record CreationRequest(String actor, String requestKey, String orderId, UUID taskId) {}

  public record EvidenceRow(
      UUID id,
      UUID taskId,
      String source,
      long businessVersion,
      String payload,
      Instant fetchedAt) {}

  public record AuditRow(
      long id,
      UUID taskId,
      String actor,
      AuditType eventType,
      UUID correlationId,
      Instant createdAt) {}

  public record Snapshot(String orderId, long version, String payload, Instant fetchedAt) {}

  public record Created(TaskRow task, boolean reused) {}
}
