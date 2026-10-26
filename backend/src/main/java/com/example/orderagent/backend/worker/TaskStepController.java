package com.example.orderagent.backend.worker;

import com.example.orderagent.backend.security.WorkbenchAccount;
import com.example.orderagent.backend.task.TaskRecords.State;
import com.example.orderagent.backend.task.TaskService;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class TaskStepController {
  private final TaskService tasks;
  private final WorkerMapper mapper;

  public TaskStepController(TaskService tasks, WorkerMapper mapper) {
    this.tasks = tasks;
    this.mapper = mapper;
  }

  public record StepView(
      long id,
      long seq,
      String type,
      String status,
      State beforeState,
      State afterState,
      String output,
      Instant startedAt,
      Instant finishedAt) {}

  @GetMapping("/api/tasks/{id}/steps")
  public List<StepView> steps(
      @AuthenticationPrincipal WorkbenchAccount account,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") @Min(0) long after,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
    tasks.detail(account, id);
    return mapper.steps(id, after, limit).stream()
        .map(
            row ->
                new StepView(
                    row.id(),
                    row.seq(),
                    row.type(),
                    row.status(),
                    row.beforeState(),
                    row.afterState(),
                    row.output(),
                    row.startedAt(),
                    row.finishedAt()))
        .toList();
  }
}
