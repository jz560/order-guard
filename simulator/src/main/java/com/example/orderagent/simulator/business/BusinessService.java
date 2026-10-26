package com.example.orderagent.simulator.business;

import com.example.orderagent.simulator.business.BusinessTypes.*;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BusinessService {
  private static final Logger LOG = LoggerFactory.getLogger(BusinessService.class);
  private final BusinessMapper mapper;
  private final DemoMode demoMode;

  public BusinessService(BusinessMapper mapper, DemoMode demoMode) {
    this.mapper = mapper;
    this.demoMode = demoMode;
  }

  public Order order(String id) {
    return required(mapper.order(id));
  }

  public Payment payment(String id) {
    return required(mapper.payment(id));
  }

  public List<Callback> callbacks(String id) {
    payment(id);
    return mapper.callbacks(id);
  }

  public Operation operation(String id) {
    var operation = required(mapper.operation(id));
    var order = mapper.order(operation.orderId());
    if (demoMode.enabled() && order != null) {
      var scenario = mapper.scenario(order.scenario());
      if (scenario.fault() == Fault.QUERY_UNAVAILABLE
          && order.id().equals(scenario.activeOrderId()))
        throw new BusinessException(503, "RESULT_UNAVAILABLE", "演示故障：操作结果暂不可查询");
    }
    return operation;
  }

  private static <T> T required(T value) {
    if (value == null) throw new BusinessException(404, "NOT_FOUND", "未找到记录；操作记录不存在不代表未执行");
    return value;
  }

  @Transactional
  public Execution execute(String id, RepairRequest request) {
    mapper.reserve(id, request);
    var operation = mapper.lockOperation(id);
    if (!operation.matches(request))
      throw new BusinessException(409, "IDEMPOTENCY_CONFLICT", "操作 ID 已绑定其他参数");
    if (operation.status() != ResultStatus.IN_PROGRESS) return new Execution(operation, false);
    var snapshot = mapper.order(request.orderId());
    // 与场景重置使用相同锁顺序，避免旧订单在新一轮演示中被推进。
    var scenario = snapshot == null ? null : mapper.lockScenario(snapshot.scenario());
    var order = mapper.lockOrder(request.orderId());
    var payment = order == null ? null : mapper.lockPayment(order.paymentId());
    String code = rejection(request, order, payment, scenario);
    long before = order == null ? 0 : order.version();
    boolean success = code == null;
    if (success && mapper.advance(order.id(), before) != 1)
      throw new IllegalStateException("订单条件更新失败");
    mapper.complete(
        id,
        success ? ResultStatus.SUCCEEDED : ResultStatus.REJECTED,
        success ? "VERIFIED_AND_ADVANCED" : code,
        before,
        success ? before + 1 : before);
    boolean delay =
        demoMode.enabled() && scenario != null && scenario.fault() == Fault.DELAY_RESPONSE_ONCE;
    if (delay) mapper.fault(scenario.scenario(), Fault.NONE);
    // 返回后 Spring 提交事务；响应延迟发生在 Controller 中，不能持有数据库锁等待。
    LOG.info(
        "simulator operation prepared operationId={} orderId={} outcome={}",
        id,
        request.orderId(),
        success ? "VERIFIED_AND_ADVANCED" : code);
    return new Execution(mapper.operation(id), delay);
  }

  static String rejection(
      RepairRequest request, Order order, Payment payment, ScenarioState scenario) {
    if (order == null) return "ORDER_NOT_FOUND";
    if (scenario == null || !order.id().equals(scenario.activeOrderId()))
      return "SCENARIO_REPLACED";
    if (order.status() != OrderStatus.PENDING_PAYMENT) return "ORDER_NOT_PENDING";
    if (order.version() != request.expectedVersion()) return "ORDER_VERSION_CHANGED";
    if (payment == null) return "PAYMENT_EVIDENCE_MISSING";
    if (!order.paymentId().equals(request.paymentId()) || !payment.orderId().equals(order.id()))
      return "PAYMENT_LINK_MISMATCH";
    if (payment.status() != PaymentStatus.SUCCESS) return "PAYMENT_NOT_SUCCESSFUL";
    if (order.amountMinor() != request.amountMinor()
        || payment.amountMinor() != order.amountMinor()) return "AMOUNT_MISMATCH";
    if (!order.currency().equals(request.currency())
        || !payment.currency().equals(order.currency())) return "CURRENCY_MISMATCH";
    return null;
  }
}
