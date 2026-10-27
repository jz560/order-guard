package com.example.orderagent.backend.execution;

import com.example.orderagent.backend.approval.PlanRecords.Action;
import com.example.orderagent.backend.task.SimulatorOrderClient.Order;
import com.example.orderagent.backend.worker.WorkerRecords.Claim;
import java.time.Instant;
import java.util.UUID;

public final class ExecutionRecords {
  private ExecutionRecords() {}

  public enum Status {
    EXECUTING,
    UNKNOWN,
    VERIFYING,
    SUCCEEDED,
    REJECTED,
    MANUAL
  }

  public record Params(
      Action action,
      long amountMinor,
      String currency,
      long expectedVersion,
      String orderId,
      String paymentId) {
    public boolean valid(String taskOrder) {
      return action == Action.VERIFY_PAYMENT_AND_ADVANCE_ORDER
          && amountMinor > 0
          && expectedVersion > 0
          && expectedVersion < Long.MAX_VALUE
          && "CNY".equals(currency)
          && taskOrder.equals(orderId)
          && orderId.matches("[A-Za-z0-9_-]{1,100}")
          && paymentId != null
          && paymentId.matches("[A-Za-z0-9_-]{1,100}");
    }
  }

  public record Payment(
      String id,
      String orderId,
      String status,
      long amountMinor,
      String currency,
      Instant paidAt) {}

  public record Facts(Order order, Payment payment, Instant fetchedAt) {
    public boolean matches(Params p, boolean finalCheck, long finalVersion) {
      return order != null
          && payment != null
          && p.orderId().equals(order.id())
          && p.paymentId().equals(order.paymentId())
          && p.paymentId().equals(payment.id())
          && p.orderId().equals(payment.orderId())
          && "SUCCESS".equals(payment.status())
          && Long.valueOf(p.amountMinor()).equals(order.amountMinor())
          && p.amountMinor() == payment.amountMinor()
          && p.currency().equals(order.currency())
          && p.currency().equals(payment.currency())
          && (finalCheck ? "PAID" : "PENDING_PAYMENT").equals(order.status())
          && Long.valueOf(finalCheck ? finalVersion : p.expectedVersion()).equals(order.version());
    }
  }

  public record Operation(
      String operationId,
      String orderId,
      String paymentId,
      long amountMinor,
      String currency,
      long expectedVersion,
      Action action,
      String status,
      String code,
      String message,
      long beforeVersion,
      long afterVersion,
      Instant completedAt) {
    public boolean matches(UUID id, Params p) {
      return id.toString().equals(operationId)
          && p.orderId().equals(orderId)
          && p.paymentId().equals(paymentId)
          && p.amountMinor() == amountMinor
          && p.currency().equals(currency)
          && p.expectedVersion() == expectedVersion
          && p.action() == action;
    }

    public boolean succeeded(Params p) {
      return "SUCCEEDED".equals(status)
          && "VERIFIED_AND_ADVANCED".equals(code)
          && completedAt != null
          && beforeVersion == p.expectedVersion()
          && afterVersion == beforeVersion + 1;
    }
  }

  public record ExecutionRow(
      UUID operationId,
      UUID taskId,
      UUID planId,
      String paramsHash,
      Status status,
      int checks,
      String result,
      String lastError,
      Instant createdAt,
      Instant deadlineAt,
      Instant updatedAt) {}

  public record Started(Claim claim, ExecutionRow execution, Params params) {}
}
