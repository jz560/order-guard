package com.example.orderagent.backend.execution;

import static org.assertj.core.api.Assertions.*;

import com.example.orderagent.backend.approval.PlanRecords.Action;
import com.example.orderagent.backend.execution.ExecutionRecords.*;
import com.example.orderagent.backend.task.SimulatorOrderClient.Order;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExecutionPolicyTest {
  final Params params =
      new Params(Action.VERIFY_PAYMENT_AND_ADVANCE_ORDER, 10000, "CNY", 1, "ORDER-1", "PAY-1");

  Facts facts(String status, long version, String paymentOrder, String currency, long amount) {
    return new Facts(
        new Order("ORDER-1", "CALLBACK_FAILED", status, 10000L, "CNY", "PAY-1", version),
        new Payment("PAY-1", paymentOrder, "SUCCESS", amount, currency, Instant.now()),
        Instant.now());
  }

  @Test
  void onlyMatchingPendingOrderAndSuccessfulPaymentPassPrecheck() {
    assertThat(facts("PENDING_PAYMENT", 1, "ORDER-1", "CNY", 10000).matches(params, false, 0))
        .isTrue();
    assertThat(facts("PAID", 2, "ORDER-1", "CNY", 10000).matches(params, false, 0)).isFalse();
    assertThat(facts("PENDING_PAYMENT", 2, "ORDER-1", "CNY", 10000).matches(params, false, 0))
        .isFalse();
    assertThat(facts("PENDING_PAYMENT", 1, "OTHER", "CNY", 10000).matches(params, false, 0))
        .isFalse();
    assertThat(facts("PENDING_PAYMENT", 1, "ORDER-1", "USD", 10000).matches(params, false, 0))
        .isFalse();
    assertThat(facts("PENDING_PAYMENT", 1, "ORDER-1", "CNY", 9999).matches(params, false, 0))
        .isFalse();
  }

  @Test
  void finalVerificationRequiresPaidStateAndExactReturnedVersion() {
    assertThat(facts("PAID", 2, "ORDER-1", "CNY", 10000).matches(params, true, 2)).isTrue();
    assertThat(facts("PENDING_PAYMENT", 1, "ORDER-1", "CNY", 10000).matches(params, true, 2))
        .isFalse();
    assertThat(facts("PAID", 3, "ORDER-1", "CNY", 10000).matches(params, true, 2)).isFalse();
  }

  @Test
  void anotherOperationOrDifferentFrozenParametersCannotBeAccepted() {
    UUID id = UUID.randomUUID();
    var result =
        new Operation(
            id.toString(),
            "ORDER-1",
            "PAY-1",
            10000,
            "CNY",
            1,
            params.action(),
            "SUCCEEDED",
            "VERIFIED_AND_ADVANCED",
            "",
            1,
            2,
            Instant.now());
    assertThat(result.matches(id, params)).isTrue();
    assertThat(result.succeeded(params)).isTrue();
    assertThat(result.matches(UUID.randomUUID(), params)).isFalse();
    assertThat(result.matches(id, new Params(params.action(), 9999, "CNY", 1, "ORDER-1", "PAY-1")))
        .isFalse();
    assertThat(
            new Params(params.action(), 10000, "CNY", Long.MAX_VALUE, "ORDER-1", "PAY-1")
                .valid("ORDER-1"))
        .isFalse();
    assertThat(params.valid("OTHER")).isFalse();
  }
}
