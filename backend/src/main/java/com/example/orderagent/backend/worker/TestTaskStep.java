package com.example.orderagent.backend.worker;

import com.example.orderagent.backend.task.TaskRecords.State;
import com.example.orderagent.backend.worker.WorkerRecords.*;
import org.springframework.stereotype.Component;

/** T07 只验证后台推进；此替身不产生真实诊断结论、审批方案或业务写入。 */
@Component
public class TestTaskStep {
  private final WorkerSettings settings;

  public TestTaskStep(WorkerSettings settings) {
    this.settings = settings;
  }

  public Result run(Claim claim) {
    return switch (claim.state()) {
      case QUEUED ->
          new Result(
              State.DIAGNOSING, "TEST_START", "T07 测试步骤：进入诊断阶段，尚未调用模型", settings.stepDelayMillis());
      case DIAGNOSING ->
          new Result(State.WAITING_APPROVAL, "TEST_WAIT", "T07 测试步骤：验证等待审批时释放资源；没有真实诊断结论或可审批方案", 0);
      default -> throw new IllegalArgumentException("此状态没有 T07 测试步骤");
    };
  }
}
