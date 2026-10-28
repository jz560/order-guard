package com.example.orderagent.backend.diagnosis;

import com.example.orderagent.backend.security.WorkbenchAccount;
import com.example.orderagent.backend.task.TaskService;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class DiagnosisController {
  private final DiagnosisJournal journal;
  private final TaskService tasks;

  public DiagnosisController(DiagnosisJournal journal, TaskService tasks) {
    this.journal = journal;
    this.tasks = tasks;
  }

  public record View(DiagnosisJournal.Run run, List<DiagnosisJournal.Event> events) {}

  @GetMapping("/api/tasks/{id}/diagnosis")
  public View get(@AuthenticationPrincipal WorkbenchAccount account, @PathVariable UUID id) {
    tasks.detail(account, id);
    return new View(journal.run(id), journal.events(id));
  }
}
