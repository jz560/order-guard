package com.example.orderagent.backend.execution;

import com.example.orderagent.backend.api.ApiException;
import com.example.orderagent.backend.execution.ExecutionRecords.Status;
import com.example.orderagent.backend.security.WorkbenchAccount;
import com.example.orderagent.backend.task.TaskService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class ExecutionController {
  private final TaskService tasks;
  private final ExecutionMapper mapper;
  private final JsonMapper json;

  public ExecutionController(TaskService tasks, ExecutionMapper mapper, JsonMapper json) {
    this.tasks = tasks;
    this.mapper = mapper;
    this.json = json;
  }

  public record View(
      UUID operationId,
      UUID planId,
      Status status,
      int checks,
      JsonNode result,
      String lastError,
      Instant createdAt,
      Instant deadlineAt,
      Instant updatedAt) {}

  @GetMapping("/api/tasks/{id}/execution")
  public View detail(@AuthenticationPrincipal WorkbenchAccount account, @PathVariable UUID id) {
    tasks.detail(account, id);
    var ex = mapper.execution(id);
    if (ex == null) throw new ApiException(404, "EXECUTION_NOT_FOUND", "任务尚无执行意图");
    return new View(
        ex.operationId(),
        ex.planId(),
        ex.status(),
        ex.checks(),
        ex.result() == null ? null : json.readTree(ex.result()),
        ex.lastError(),
        ex.createdAt(),
        ex.deadlineAt(),
        ex.updatedAt());
  }
}
