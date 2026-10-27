package com.example.orderagent.backend.approval;

import com.example.orderagent.backend.api.ApiErrors;
import com.example.orderagent.backend.approval.PlanRecords.*;
import com.example.orderagent.backend.security.WorkbenchAccount;
import com.example.orderagent.backend.task.*;
import com.example.orderagent.backend.task.TaskRecords.State;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class PlanController {
  private final PlanService service;
  private final PlanMapper mapper;
  private final TaskService tasks;
  private final JsonMapper json;

  public PlanController(
      PlanService service, PlanMapper mapper, TaskService tasks, JsonMapper json) {
    this.service = service;
    this.mapper = mapper;
    this.tasks = tasks;
    this.json = json;
  }

  public record DecisionRequest(
      @Positive long version,
      @NotNull @Pattern(regexp = "[0-9a-f]{64}") String paramsHash,
      @NotNull Decision decision,
      @Size(max = 1000) String reason) {}

  public record DecisionView(
      Decision decision, String approverId, String reason, Instant decidedAt) {}

  public record PlanView(
      UUID id,
      UUID taskId,
      long version,
      Action action,
      JsonNode params,
      String paramsHash,
      UUID evidenceId,
      String source,
      Instant createdAt,
      Instant expiresAt,
      Instant serverTime,
      boolean expired,
      State taskState,
      DecisionView approval,
      List<String> allowedActions) {}

  private PlanView view(WorkbenchAccount account, PlanRow plan) {
    var task = tasks.detail(account, plan.taskId());
    var approval = mapper.approval(plan.id());
    var now = mapper.now();
    boolean expired = !plan.expiresAt().isAfter(now);
    boolean allowed =
        account.role().equals("APPROVER")
            && !expired
            && approval == null
            && task.state() == State.WAITING_APPROVAL
            && mapper.latest(task.id()).id().equals(plan.id());
    return new PlanView(
        plan.id(),
        plan.taskId(),
        plan.version(),
        plan.action(),
        json.readTree(plan.canonicalParams()),
        plan.paramsHash(),
        plan.evidenceId(),
        plan.source(),
        plan.createdAt(),
        plan.expiresAt(),
        now,
        expired,
        task.state(),
        approval == null
            ? null
            : new DecisionView(
                approval.decision(),
                approval.approverId(),
                approval.reason(),
                approval.decidedAt()),
        allowed ? List.of("APPROVE", "REJECT") : List.of());
  }

  @GetMapping("/api/tasks/{id}/plan")
  public PlanView current(
      @AuthenticationPrincipal WorkbenchAccount account, @PathVariable UUID id) {
    return view(account, service.current(account, id));
  }

  @GetMapping("/api/plans/{id}")
  public PlanView detail(@AuthenticationPrincipal WorkbenchAccount account, @PathVariable UUID id) {
    return view(account, service.accessible(account, id));
  }

  @PostMapping("/api/plans/{id}/decision")
  public PlanView decide(
      @AuthenticationPrincipal WorkbenchAccount account,
      @PathVariable UUID id,
      @Valid @RequestBody DecisionRequest request,
      HttpServletRequest http) {
    return view(
        account,
        service.decide(
            account,
            id,
            request.version(),
            request.paramsHash(),
            request.decision(),
            request.reason(),
            UUID.fromString(ApiErrors.correlation(http))));
  }
}
