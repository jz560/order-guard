package com.example.orderagent.simulator.business;

import jakarta.validation.constraints.*;
import java.time.Instant;

public final class BusinessTypes {
  private BusinessTypes() {}

  public enum Scenario {
    CALLBACK_FAILED,
    NORMAL_DELAY,
    AMOUNT_CONFLICT,
    MISSING_EVIDENCE,
    ALREADY_REPAIRED,
    ORDER_CLOSED
  }

  public enum Fault {
    NONE,
    DELAY_RESPONSE_ONCE,
    QUERY_UNAVAILABLE
  }

  public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    CLOSED
  }

  public enum PaymentStatus {
    SUCCESS,
    PENDING,
    FAILED
  }

  public enum CallbackResult {
    FAILED,
    PENDING,
    SUCCEEDED
  }

  public enum Action {
    VERIFY_PAYMENT_AND_ADVANCE_ORDER
  }

  public enum ResultStatus {
    IN_PROGRESS,
    SUCCEEDED,
    REJECTED
  }

  public record Order(
      String id,
      Scenario scenario,
      OrderStatus status,
      long amountMinor,
      String currency,
      String paymentId,
      long version) {}

  public record Payment(
      String id,
      String orderId,
      PaymentStatus status,
      long amountMinor,
      String currency,
      Instant paidAt) {}

  public record Callback(
      String id, String paymentId, Instant receivedAt, CallbackResult result, String errorCode) {}

  public record ScenarioState(Scenario scenario, String activeOrderId, Fault fault) {}

  public record RepairRequest(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String orderId,
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String paymentId,
      @NotNull @Positive Long amountMinor,
      @NotNull @Pattern(regexp = "CNY") String currency,
      @NotNull @Positive Long expectedVersion,
      @NotNull Action action) {}

  public record Operation(
      String operationId,
      String orderId,
      String paymentId,
      long amountMinor,
      String currency,
      long expectedVersion,
      Action action,
      ResultStatus status,
      String code,
      long beforeVersion,
      long afterVersion,
      Instant completedAt) {
    public boolean matches(RepairRequest request) {
      return orderId.equals(request.orderId())
          && paymentId.equals(request.paymentId())
          && amountMinor == request.amountMinor()
          && currency.equals(request.currency())
          && expectedVersion == request.expectedVersion()
          && action == request.action();
    }
  }

  public record Execution(Operation operation, boolean delayResponse) {}

  public record FaultRequest(@NotNull Fault fault) {}
}
