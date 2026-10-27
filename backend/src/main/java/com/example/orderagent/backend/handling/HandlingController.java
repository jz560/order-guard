package com.example.orderagent.backend.handling;

import com.example.orderagent.backend.api.ApiErrors;
import com.example.orderagent.backend.handling.HandlingRecords.*;
import com.example.orderagent.backend.security.WorkbenchAccount;
import com.example.orderagent.backend.task.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tasks/{id}")
public class HandlingController {
  private final HandlingService service;
  private final HandlingMapper mapper;
  private final TaskService tasks;

  public HandlingController(HandlingService service, HandlingMapper mapper, TaskService tasks) {
    this.service = service;
    this.mapper = mapper;
    this.tasks = tasks;
  }

  public record Request(@Positive long version, @NotBlank @Size(max = 1000) String reason) {}

  public record CancelRequest(@Positive long version, @Size(max = 1000) String reason) {}

  public record ResolveRequest(
      @Positive long version,
      @NotBlank @Size(max = 1000) String reason,
      @NotNull Resolution resolution) {}

  @PostMapping("/cancel")
  public HandlingRecords.Record cancel(
      @AuthenticationPrincipal WorkbenchAccount account,
      @PathVariable UUID id,
      @Valid @RequestBody CancelRequest request,
      HttpServletRequest http) {
    return service.handle(
        account,
        id,
        Action.CANCEL,
        request.version(),
        request.reason() == null ? "用户取消任务" : request.reason(),
        null,
        UUID.fromString(ApiErrors.correlation(http)));
  }

  @PostMapping("/takeover")
  public HandlingRecords.Record takeover(
      @AuthenticationPrincipal WorkbenchAccount account,
      @PathVariable UUID id,
      @Valid @RequestBody Request request,
      HttpServletRequest http) {
    return service.handle(
        account,
        id,
        Action.TAKEOVER,
        request.version(),
        request.reason(),
        null,
        UUID.fromString(ApiErrors.correlation(http)));
  }

  @PostMapping("/resolve")
  public HandlingRecords.Record resolve(
      @AuthenticationPrincipal WorkbenchAccount account,
      @PathVariable UUID id,
      @Valid @RequestBody ResolveRequest request,
      HttpServletRequest http) {
    return service.handle(
        account,
        id,
        Action.RESOLVE,
        request.version(),
        request.reason(),
        request.resolution(),
        UUID.fromString(ApiErrors.correlation(http)));
  }

  @GetMapping("/handling")
  public List<HandlingRecords.Record> history(
      @AuthenticationPrincipal WorkbenchAccount account, @PathVariable UUID id) {
    tasks.detail(account, id);
    return mapper.history(id);
  }
}
