package com.example.orderagent.simulator.business;

import static org.assertj.core.api.Assertions.*;

import com.example.orderagent.simulator.business.BusinessTypes.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class BusinessPolicyTest {
  final Order order =
      new Order("o", Scenario.CALLBACK_FAILED, OrderStatus.PENDING_PAYMENT, 10000, "CNY", "p", 1);
  final ScenarioState scenario = new ScenarioState(Scenario.CALLBACK_FAILED, "o", Fault.NONE);
  final RepairRequest request =
      new RepairRequest("o", "p", 10000L, "CNY", 1L, Action.VERIFY_PAYMENT_AND_ADVANCE_ORDER);

  Payment payment(String orderId, PaymentStatus status, long amount, String currency) {
    return new Payment("p", orderId, status, amount, currency, Instant.now());
  }

  @Test
  void paymentFactsAndVersionAreRequired() {
    assertThat(
            BusinessService.rejection(
                request, order, payment("o", PaymentStatus.PENDING, 10000, "CNY"), scenario))
        .isEqualTo("PAYMENT_NOT_SUCCESSFUL");
    assertThat(
            BusinessService.rejection(
                request, order, payment("other", PaymentStatus.SUCCESS, 10000, "CNY"), scenario))
        .isEqualTo("PAYMENT_LINK_MISMATCH");
    assertThat(
            BusinessService.rejection(
                request, order, payment("o", PaymentStatus.SUCCESS, 9900, "CNY"), scenario))
        .isEqualTo("AMOUNT_MISMATCH");
    assertThat(
            BusinessService.rejection(
                request, order, payment("o", PaymentStatus.SUCCESS, 10000, "USD"), scenario))
        .isEqualTo("CURRENCY_MISMATCH");
    assertThat(
            BusinessService.rejection(
                new RepairRequest("o", "p", 10000L, "CNY", 2L, request.action()),
                order,
                payment("o", PaymentStatus.SUCCESS, 10000, "CNY"),
                scenario))
        .isEqualTo("ORDER_VERSION_CHANGED");
    assertThat(BusinessService.rejection(request, null, null, null)).isEqualTo("ORDER_NOT_FOUND");
  }

  @Test
  void onlyExclusiveDemoOrTestProfilesEnableAdministration() {
    var env = new MockEnvironment();
    env.setDefaultProfiles("demo");
    assertThat(new DemoMode(env).enabled()).isTrue();
    env.setActiveProfiles("test");
    assertThat(new DemoMode(env).enabled()).isTrue();
    env.setActiveProfiles("production", "demo");
    var mode = new DemoMode(env);
    assertThat(mode.enabled()).isFalse();
    assertThatThrownBy(mode::require).isInstanceOf(BusinessException.class);
    env.setActiveProfiles("production");
    assertThat(new DemoMode(env).enabled()).isFalse();
  }
}
