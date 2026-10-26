package com.example.orderagent.simulator.business;

import com.example.orderagent.simulator.business.BusinessTypes.*;
import java.time.Instant;

// HTTP 契约与 MyBatis 持久化记录分离，新增存储字段不自动暴露给调用方。
public final class BusinessResponses {
  private BusinessResponses() {}

  public record OrderView(
      String id,
      Scenario scenario,
      OrderStatus status,
      long amountMinor,
      String currency,
      String paymentId,
      long version) {
    static OrderView from(Order value) {
      return new OrderView(
          value.id(),
          value.scenario(),
          value.status(),
          value.amountMinor(),
          value.currency(),
          value.paymentId(),
          value.version());
    }
  }

  public record PaymentView(
      String id,
      String orderId,
      PaymentStatus status,
      long amountMinor,
      String currency,
      Instant paidAt) {
    static PaymentView from(Payment value) {
      return new PaymentView(
          value.id(),
          value.orderId(),
          value.status(),
          value.amountMinor(),
          value.currency(),
          value.paidAt());
    }
  }

  public record CallbackView(
      String id, String paymentId, Instant receivedAt, CallbackResult result, String errorCode) {
    static CallbackView from(Callback value) {
      return new CallbackView(
          value.id(), value.paymentId(), value.receivedAt(), value.result(), value.errorCode());
    }
  }

  public record ScenarioView(Scenario scenario, String activeOrderId, Fault fault) {
    static ScenarioView from(ScenarioState value) {
      return new ScenarioView(value.scenario(), value.activeOrderId(), value.fault());
    }
  }

  public record OperationView(
      String operationId,
      String orderId,
      String paymentId,
      long amountMinor,
      String currency,
      long expectedVersion,
      Action action,
      ResultStatus status,
      String code,
      String message,
      long beforeVersion,
      long afterVersion,
      Instant completedAt) {
    static OperationView from(Operation value) {
      String message =
          switch (value.code()) {
            case "VERIFIED_AND_ADVANCED" -> "支付事实核验成功，订单已推进";
            case "ORDER_NOT_FOUND" -> "未找到订单";
            case "SCENARIO_REPLACED" -> "此订单已不属于当前演示轮次，请重新查询场景";
            case "ORDER_NOT_PENDING" -> "订单已支付或已关闭，不能推进";
            case "ORDER_VERSION_CHANGED" -> "订单版本已变化，请重新核验";
            case "PAYMENT_EVIDENCE_MISSING" -> "缺少支付证据";
            case "PAYMENT_LINK_MISMATCH" -> "订单与支付关联不一致";
            case "PAYMENT_NOT_SUCCESSFUL" -> "支付尚未成功";
            case "AMOUNT_MISMATCH" -> "订单、支付或请求金额不一致";
            case "CURRENCY_MISMATCH" -> "订单、支付或请求币种不一致";
            default -> "操作结果待确认";
          };
      return new OperationView(
          value.operationId(),
          value.orderId(),
          value.paymentId(),
          value.amountMinor(),
          value.currency(),
          value.expectedVersion(),
          value.action(),
          value.status(),
          value.code(),
          message,
          value.beforeVersion(),
          value.afterVersion(),
          value.completedAt());
    }
  }
}
