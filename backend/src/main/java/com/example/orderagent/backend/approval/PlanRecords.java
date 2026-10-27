package com.example.orderagent.backend.approval;

import java.time.Instant;
import java.util.UUID;

public final class PlanRecords {
  private PlanRecords() {}

  public enum Action {
    VERIFY_PAYMENT_AND_ADVANCE_ORDER
  }

  public enum Decision {
    APPROVE,
    REJECT
  }

  public record PlanRow(
      UUID id,
      UUID taskId,
      long version,
      Action action,
      String canonicalParams,
      String paramsHash,
      UUID evidenceId,
      String source,
      Instant createdAt,
      Instant expiresAt) {}

  public record ApprovalRow(
      UUID id,
      UUID planId,
      Decision decision,
      String approverId,
      String reason,
      Instant decidedAt) {}
}
