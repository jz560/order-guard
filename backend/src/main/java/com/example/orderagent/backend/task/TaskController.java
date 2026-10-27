package com.example.orderagent.backend.task;

import com.example.orderagent.backend.api.*;
import com.example.orderagent.backend.security.WorkbenchAccount;
import com.example.orderagent.backend.task.TaskRecords.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
  private final TaskService service;
  private final JsonMapper json;

  public TaskController(TaskService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  public record CreateRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String orderId) {}

  public record TaskView(
      UUID id,
      String orderId,
      State state,
      String outcome,
      long version,
      String createdBy,
      Instant createdAt,
      Instant updatedAt,
      Instant nextRunAt,
      List<String> allowedActions) {
    static List<String> actions(TaskRow row, WorkbenchAccount account) {
      var actions =
          new ArrayList<>(
              List.of(
                  "VIEW_EVIDENCE",
                  "VIEW_AUDIT",
                  "VIEW_STEPS",
                  "VIEW_PLAN",
                  "VIEW_EXECUTION",
                  "VIEW_HANDLING"));
      if (account.role().equals("OPERATOR")
          && Set.of(State.QUEUED, State.DIAGNOSING, State.WAITING_APPROVAL, State.APPROVED)
              .contains(row.state())) actions.add("CANCEL");
      if (account.role().equals("APPROVER") && row.state() != State.FINISHED)
        actions.add(row.state() == State.MANUAL ? "RESOLVE_MANUAL" : "TAKEOVER");
      return List.copyOf(actions);
    }

    static TaskView from(TaskRow row, WorkbenchAccount account) {
      return new TaskView(
          row.id(),
          row.orderId(),
          row.state(),
          row.outcome(),
          row.version(),
          row.createdBy(),
          row.createdAt(),
          row.updatedAt(),
          row.nextRunAt(),
          actions(row, account));
    }
  }

  public record CreateResult(TaskView task, boolean reused) {}

  public record Page(List<TaskView> items, long total, int page, int size) {}

  public record EvidenceView(
      UUID id, String source, long businessVersion, JsonNode payload, Instant fetchedAt) {}

  public record AuditView(
      long id, String actor, AuditType eventType, UUID correlationId, Instant createdAt) {}

  @PostMapping
  public ResponseEntity<CreateResult> create(
      @AuthenticationPrincipal WorkbenchAccount account,
      @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String key,
      @Valid @RequestBody CreateRequest request,
      HttpServletRequest http) {
    var result =
        service.create(
            account, key, request.orderId(), UUID.fromString(ApiErrors.correlation(http)));
    return ResponseEntity.status(result.reused() ? 200 : 201)
        .location(URI.create("/api/tasks/" + result.task().id()))
        .body(new CreateResult(TaskView.from(result.task(), account), result.reused()));
  }

  @GetMapping
  public Page list(
      @AuthenticationPrincipal WorkbenchAccount account,
      @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String orderId,
      @RequestParam(required = false) State state,
      @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return new Page(
        service.list(account, orderId, state, page, size).stream()
            .map(row -> TaskView.from(row, account))
            .toList(),
        service.count(account, orderId, state),
        page,
        size);
  }

  @GetMapping("/{id}")
  public TaskView detail(@AuthenticationPrincipal WorkbenchAccount account, @PathVariable UUID id) {
    return TaskView.from(service.detail(account, id), account);
  }

  @GetMapping("/{id}/evidence")
  public List<EvidenceView> evidence(
      @AuthenticationPrincipal WorkbenchAccount account, @PathVariable UUID id) {
    return service.evidence(account, id).stream()
        .map(
            row ->
                new EvidenceView(
                    row.id(),
                    row.source(),
                    row.businessVersion(),
                    json.readTree(row.payload()),
                    row.fetchedAt()))
        .toList();
  }

  @GetMapping("/{id}/audit")
  public List<AuditView> audit(
      @AuthenticationPrincipal WorkbenchAccount account,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") @Min(0) long after,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
    return service.audit(account, id, after, limit).stream()
        .map(
            row ->
                new AuditView(
                    row.id(), row.actor(), row.eventType(), row.correlationId(), row.createdAt()))
        .toList();
  }
}
