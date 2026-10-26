package com.example.orderagent.simulator.business;

import com.example.orderagent.simulator.business.BusinessResponses.*;
import com.example.orderagent.simulator.business.BusinessTypes.*;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class BusinessController {
  private final BusinessService service;
  private final ScenarioService scenarios;

  public BusinessController(BusinessService service, ScenarioService scenarios) {
    this.service = service;
    this.scenarios = scenarios;
  }

  @GetMapping("/internal/orders/{id}")
  public OrderView order(@PathVariable String id) {
    return OrderView.from(service.order(id));
  }

  @GetMapping("/internal/payments/{id}")
  public PaymentView payment(@PathVariable String id) {
    return PaymentView.from(service.payment(id));
  }

  @GetMapping("/internal/payments/{id}/callbacks")
  public List<CallbackView> callbacks(@PathVariable String id) {
    return service.callbacks(id).stream().map(CallbackView::from).toList();
  }

  @GetMapping("/internal/operations/{id}")
  public OperationView operation(@PathVariable String id) {
    return OperationView.from(service.operation(id));
  }

  @PutMapping("/internal/operations/{id}")
  public ResponseEntity<OperationView> execute(
      @PathVariable String id, @Valid @RequestBody RepairRequest request)
      throws InterruptedException {
    if (!id.matches("[A-Za-z0-9_-]{1,100}"))
      throw new BusinessException(400, "INVALID_REQUEST", "操作 ID 格式不正确");
    var result = service.execute(id, request);
    if (result.delayResponse()) Thread.sleep(2000);
    return ResponseEntity.status(result.operation().status() == ResultStatus.REJECTED ? 422 : 200)
        .body(OperationView.from(result.operation()));
  }

  @GetMapping("/internal/demo/scenarios")
  public List<ScenarioView> scenarios() {
    return scenarios.list().stream().map(ScenarioView::from).toList();
  }

  @PostMapping("/internal/demo/scenarios/{scenario}/reset")
  public ScenarioView reset(@PathVariable Scenario scenario) {
    return ScenarioView.from(scenarios.reset(scenario));
  }

  @PutMapping("/internal/demo/scenarios/{scenario}/fault")
  public ScenarioView fault(
      @PathVariable Scenario scenario, @Valid @RequestBody FaultRequest request) {
    return ScenarioView.from(scenarios.fault(scenario, request.fault()));
  }
}
