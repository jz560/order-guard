package com.example.orderagent.backend.diagnosis;

import static org.mockito.Mockito.*;

import com.example.orderagent.backend.task.*;
import com.example.orderagent.backend.task.TaskRecords.State;
import com.example.orderagent.backend.worker.WorkerRecords.Claim;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.mock.env.MockEnvironment;

class TaskDiagnosisTest {
  final DiagnosisTransactions tx = mock(DiagnosisTransactions.class);
  final DiagnosisJournal journal = mock(DiagnosisJournal.class);
  final TaskMapper tasks = mock(TaskMapper.class);
  final TaskEvidenceTools tools = mock(TaskEvidenceTools.class);
  final ModelGateway model = mock(ModelGateway.class);
  final Claim claim =
      new Claim(
          UUID.randomUUID(),
          "O1",
          State.DIAGNOSING,
          3,
          "w",
          2,
          Instant.now().plusSeconds(30),
          0,
          Instant.now());

  TaskDiagnosis engine() {
    var s =
        new DiagnosisSettings(
            new MockEnvironment()
                .withProperty("agent.model.mode", "deepseek")
                .withProperty("agent.diagnosis.timeout-ms", "100"));
    when(tx.reserveModel(claim)).thenReturn(true);
    when(tx.response(eq(claim), any())).thenReturn(true);
    when(journal.run(claim.taskId()))
        .thenReturn(
            new DiagnosisJournal.Run(
                claim.taskId(), 1, 0, 0, Instant.now(), Instant.now().plusSeconds(120), null));
    when(tasks.evidence(claim.taskId())).thenReturn(List.of());
    return new TaskDiagnosis(s, tx, journal, tasks, tools, model);
  }

  @Test
  void invalidModelOutputCannotEnterApproval() {
    var engine = engine();
    when(model.call(any()))
        .thenReturn(
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage(
                            "{\"recommendation\":\"RECOMMEND_RECHECK\",\"reason\":\"do it\",\"evidenceIds\":[]}")))));
    engine.run(claim);
    verify(tx).stop(claim, "INVALID_DIAGNOSIS_OR_TOOL");
    verify(tx)
        .response(
            eq(claim),
            argThat(
                value ->
                    value instanceof Map<?, ?> record
                        && "unknown".equals(record.get("inputTokens"))));
    verify(tx, never()).conclude(any(), any(), any(), anyMap(), anyMap());
    verifyNoInteractions(tools);
  }

  @Test
  void crossOrderToolIsBlockedBeforeNetworkAndIntent() {
    var engine = engine();
    var output =
        AssistantMessage.builder()
            .content("")
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall(
                        "1", "function", "query_order", "{\"orderId\":\"OTHER\"}")))
            .build();
    when(model.call(any())).thenReturn(new ChatResponse(List.of(new Generation(output))));
    engine.run(claim);
    verify(tx).stop(claim, "INVALID_DIAGNOSIS_OR_TOOL");
    verify(tx, never()).reserveTool(any(), anyString(), anyString());
    verifyNoInteractions(tools);
  }

  @Test
  void timeoutStopsWithoutTryingAgainOrPretendingUsageKnown() {
    var engine = engine();
    when(model.call(any()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(1000);
              return null;
            });
    engine.run(claim);
    verify(tx).stop(claim, "MODEL_TIMEOUT_USAGE_UNKNOWN");
    verify(model, times(1)).call(any());
    verifyNoInteractions(tools);
  }

  @Test
  void revokedLeaseRejectsResponseBeforeTools() {
    var engine = engine();
    when(tx.response(eq(claim), any())).thenReturn(false);
    when(model.call(any()))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{}")))));
    engine.run(claim);
    verifyNoInteractions(tools);
    verify(tx, never()).conclude(any(), any(), any(), anyMap(), anyMap());
  }
}
