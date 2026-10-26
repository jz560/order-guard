package com.example.orderagent.backend.task;

import com.example.orderagent.backend.api.ApiException;
import com.example.orderagent.backend.security.WorkbenchAccount;
import com.example.orderagent.backend.task.TaskRecords.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TaskService {
  private static final Logger LOG = LoggerFactory.getLogger(TaskService.class);
  private final TaskMapper mapper;
  private final TaskCreationTransaction creation;
  private final SimulatorOrderClient simulator;

  public TaskService(
      TaskMapper mapper, TaskCreationTransaction creation, SimulatorOrderClient simulator) {
    this.mapper = mapper;
    this.creation = creation;
    this.simulator = simulator;
  }

  public Created create(WorkbenchAccount account, String key, String orderId, UUID correlationId) {
    account.requireCreator();
    account.requireOrder(orderId);
    var prior = mapper.request(account.username(), key);
    if (prior != null && !prior.orderId().equals(orderId))
      throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "此幂等键已用于其他订单");
    if (prior != null && prior.taskId() != null)
      return new Created(detail(account, prior.taskId()), true);
    // 网络查询在事务外；重复任务可以在模拟服务不可用时继续返回持久化结果。
    Snapshot snapshot = mapper.guard(orderId) == null ? simulator.fetch(orderId) : null;
    var result = creation.create(account.username(), key, orderId, snapshot, correlationId);
    LOG.info(
        "task creation taskId={} actor={} reused={} correlationId={}",
        result.task().id(),
        account.username(),
        result.reused(),
        correlationId);
    return result;
  }

  public TaskRow detail(WorkbenchAccount account, UUID id) {
    var task = mapper.task(id);
    // 对不存在与越权任务使用相同响应，避免泄露其他范围的任务存在性。
    if (task == null || !task.orderId().startsWith(account.orderPrefix()))
      throw new ApiException(404, "TASK_NOT_FOUND", "未找到可访问的任务");
    return task;
  }

  public List<EvidenceRow> evidence(WorkbenchAccount account, UUID id) {
    detail(account, id);
    return mapper.evidence(id);
  }

  public List<AuditRow> audit(WorkbenchAccount account, UUID id, long after, int limit) {
    detail(account, id);
    return mapper.auditPage(id, after, limit);
  }

  public List<TaskRow> list(
      WorkbenchAccount account, String orderId, State state, int page, int size) {
    if (orderId != null) account.requireOrder(orderId);
    return mapper.list(account.orderPrefix(), orderId, state, size, (long) page * size);
  }

  public long count(WorkbenchAccount account, String orderId, State state) {
    if (orderId != null) account.requireOrder(orderId);
    return mapper.count(account.orderPrefix(), orderId, state);
  }
}
