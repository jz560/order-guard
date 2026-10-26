package com.example.orderagent.backend.task;

import com.example.orderagent.backend.api.ApiException;
import com.example.orderagent.backend.task.TaskRecords.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskCreationTransaction {
  private final TaskMapper mapper;

  public TaskCreationTransaction(TaskMapper mapper) {
    this.mapper = mapper;
  }

  @Transactional
  public Created create(
      String actor, String key, String orderId, Snapshot snapshot, UUID correlationId) {
    mapper.reserveRequest(actor, key, orderId);
    var request = mapper.lockRequest(actor, key);
    if (!request.orderId().equals(orderId))
      throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "此幂等键已用于其他订单");
    if (request.taskId() != null) return new Created(mapper.task(request.taskId()), true);
    UUID candidate = UUID.randomUUID();
    // 延迟外键允许先原子占用订单限制，再创建任务；事务提交时不允许孤立限制。
    boolean created = mapper.reserveGuard(orderId, candidate) == 1;
    var guard = mapper.lockGuard(orderId);
    if (created) {
      if (snapshot == null) throw new ApiException(409, "TASK_CHANGED", "订单限制已变化，请使用原幂等键重试");
      mapper.insertTask(candidate, orderId, actor);
      mapper.insertEvidence(UUID.randomUUID(), candidate, snapshot);
    }
    mapper.completeRequest(actor, key, guard.taskId());
    mapper.audit(
        guard.taskId(),
        actor,
        created ? AuditType.TASK_CREATED : AuditType.TASK_REUSED,
        correlationId);
    return new Created(mapper.task(guard.taskId()), !created);
  }
}
