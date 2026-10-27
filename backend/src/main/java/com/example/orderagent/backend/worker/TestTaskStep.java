package com.example.orderagent.backend.worker;

import com.example.orderagent.backend.task.TaskRecords.State;
import com.example.orderagent.backend.worker.WorkerRecords.*;
import org.springframework.stereotype.Component;

/** T08 固定测试诊断；方案使用持久化订单快照，不代表真实 Agent 诊断。 */
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
          new Result(
              State.WAITING_APPROVAL, "TEST_WAIT", "T08 测试方案：冻结订单快照参数，等待审批；没有真实诊断结论，不执行业务写入", 0);
      default -> throw new IllegalArgumentException("此状态没有 T07 测试步骤");
    };
  }
}
