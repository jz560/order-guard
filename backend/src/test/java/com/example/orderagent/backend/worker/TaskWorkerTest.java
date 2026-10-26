package com.example.orderagent.backend.worker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.example.orderagent.backend.task.TaskRecords.State;
import com.example.orderagent.backend.worker.WorkerRecords.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class TaskWorkerTest {
  WorkerSettings settings() {
    var env = new MockEnvironment();
    env.setActiveProfiles("test");
    return new WorkerSettings(env);
  }

  Claim claim() {
    return new Claim(
        UUID.randomUUID(),
        "T05-ORDER-1",
        State.QUEUED,
        2,
        "worker",
        1,
        Instant.now().plusSeconds(30),
        0,
        Instant.now());
  }

  @Test
  void stepFailureUsesBoundedPersistentRetry() {
    var tx = mock(WorkerTransactions.class);
    var step = mock(TestTaskStep.class);
    var claim = claim();
    when(tx.claim(anyString())).thenReturn(claim);
    when(step.run(claim)).thenThrow(new IllegalStateException("test"));
    new TaskWorker(tx, step, settings()).tick();
    verify(tx).failed(claim);
    verify(tx, never()).complete(any(), any());
  }

  @Test
  void databaseWriteFailureDoesNotMisreportStepFailureOrReleaseLease() {
    var tx = mock(WorkerTransactions.class);
    var step = mock(TestTaskStep.class);
    var claim = claim();
    var result = new Result(State.DIAGNOSING, "TEST_START", "test", 0);
    when(tx.claim(anyString())).thenReturn(claim);
    when(step.run(claim)).thenReturn(result);
    when(tx.complete(claim, result)).thenThrow(new IllegalStateException("database unavailable"));
    new TaskWorker(tx, step, settings()).tick();
    verify(tx, never()).failed(any());
  }

  @Test
  void disabledAndNonDemoWorkersNeverClaimTasks() {
    for (String profile : new String[] {"production", "production,demo"}) {
      var env = new MockEnvironment();
      env.setActiveProfiles(profile.split(","));
      var settings = new WorkerSettings(env);
      assertThat(settings.enabled()).isFalse();
      var tx = mock(WorkerTransactions.class);
      new TaskWorker(tx, mock(TestTaskStep.class), settings).tick();
      verifyNoInteractions(tx);
    }
    var env = new MockEnvironment().withProperty("agent.worker.enabled", "false");
    env.setActiveProfiles("test");
    assertThat(new WorkerSettings(env).enabled()).isFalse();
    assertThatThrownBy(() -> new WorkerSettings(env.withProperty("agent.worker.lease-ms", "0")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
