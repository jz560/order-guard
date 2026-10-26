package com.example.orderagent.simulator.business;

import com.example.orderagent.simulator.business.BusinessTypes.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScenarioService {
  private final BusinessMapper mapper;
  private final DemoMode mode;

  public ScenarioService(BusinessMapper mapper, DemoMode mode) {
    this.mapper = mapper;
    this.mode = mode;
  }

  public List<ScenarioState> list() {
    mode.require();
    return mapper.scenarios();
  }

  @Transactional
  public void initialize() {
    if (!mode.enabled()) return;
    for (var scenario : Scenario.values()) {
      mapper.seedScenario(scenario);
      if (mapper.lockScenario(scenario).activeOrderId() == null) create(scenario);
    }
  }

  @Transactional
  public ScenarioState reset(Scenario scenario) {
    mode.require();
    mapper.lockScenario(scenario);
    create(scenario);
    return mapper.scenario(scenario);
  }

  @Transactional
  public ScenarioState fault(Scenario scenario, Fault fault) {
    mode.require();
    mapper.lockScenario(scenario);
    mapper.fault(scenario, fault);
    return mapper.scenario(scenario);
  }

  private void create(Scenario scenario) {
    String run = UUID.randomUUID().toString();
    String orderId = "T05-ORDER-" + run, paymentId = "T05-PAY-" + run;
    OrderStatus status =
        switch (scenario) {
          case ALREADY_REPAIRED -> OrderStatus.PAID;
          case ORDER_CLOSED -> OrderStatus.CLOSED;
          default -> OrderStatus.PENDING_PAYMENT;
        };
    mapper.insertOrder(
        new Order(
            orderId,
            scenario,
            status,
            10000,
            "CNY",
            paymentId,
            status == OrderStatus.PAID ? 2 : 1));
    if (scenario != Scenario.MISSING_EVIDENCE) {
      // 正常延迟仅由时间与回调证据体现；下游业务核验不把诊断政策写死在接口中。
      Instant paidAt =
          scenario == Scenario.NORMAL_DELAY
              ? Instant.now()
              : Instant.now().minus(10, ChronoUnit.MINUTES);
      mapper.insertPayment(
          new Payment(
              paymentId,
              orderId,
              PaymentStatus.SUCCESS,
              scenario == Scenario.AMOUNT_CONFLICT ? 9900 : 10000,
              "CNY",
              paidAt));
      var result =
          scenario == Scenario.NORMAL_DELAY
              ? CallbackResult.PENDING
              : scenario == Scenario.ALREADY_REPAIRED
                  ? CallbackResult.SUCCEEDED
                  : CallbackResult.FAILED;
      mapper.insertCallback(
          new Callback(
              "T05-CB-" + run,
              paymentId,
              result == CallbackResult.PENDING ? null : paidAt.plusSeconds(1),
              result,
              result == CallbackResult.FAILED ? "ORDER_UPDATE_FAILED" : null));
    }
    mapper.activate(scenario, orderId);
  }
}
