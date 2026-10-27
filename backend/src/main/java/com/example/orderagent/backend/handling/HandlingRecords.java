package com.example.orderagent.backend.handling;

import java.time.Instant;
import java.util.UUID;

public final class HandlingRecords {
  private HandlingRecords() {}

  public enum Action {
    CANCEL,
    TAKEOVER,
    RESOLVE
  }

  public enum Resolution {
    NO_EXECUTION,
    CONFIRM_OPERATION
  }

  public record Record(
      UUID id,
      UUID taskId,
      Action action,
      long expectedVersion,
      String actor,
      String reason,
      Resolution resolution,
      String outcome,
      UUID operationId,
      UUID evidenceId,
      Instant createdAt) {}
}
