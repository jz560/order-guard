package com.example.orderagent.backend.task;

import com.example.orderagent.backend.task.TaskRecords.*;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TaskMapper {
  @Select("SELECT * FROM task WHERE id=#{id}")
  TaskRow task(UUID id);

  @Select("SELECT order_id,task_id,reason FROM order_guard WHERE order_id=#{orderId}")
  Guard guard(String orderId);

  @Select(
      "SELECT actor,request_key,order_id,task_id FROM task_creation_request WHERE actor=#{actor} AND request_key=#{key}")
  CreationRequest request(@Param("actor") String actor, @Param("key") String key);

  @Insert(
      "INSERT INTO task_creation_request(actor,request_key,order_id) VALUES(#{actor},#{key},#{orderId}) ON CONFLICT DO NOTHING")
  int reserveRequest(
      @Param("actor") String actor, @Param("key") String key, @Param("orderId") String orderId);

  @Select(
      "SELECT actor,request_key,order_id,task_id FROM task_creation_request WHERE actor=#{actor} AND request_key=#{key} FOR UPDATE")
  CreationRequest lockRequest(@Param("actor") String actor, @Param("key") String key);

  @Update(
      "UPDATE task_creation_request SET task_id=#{taskId} WHERE actor=#{actor} AND request_key=#{key}")
  int completeRequest(
      @Param("actor") String actor, @Param("key") String key, @Param("taskId") UUID taskId);

  @Insert(
      "INSERT INTO order_guard(order_id,task_id,reason) VALUES(#{orderId},#{taskId},'ACTIVE_TASK') ON CONFLICT(order_id) DO NOTHING")
  int reserveGuard(@Param("orderId") String orderId, @Param("taskId") UUID taskId);

  @Select("SELECT order_id,task_id,reason FROM order_guard WHERE order_id=#{orderId} FOR UPDATE")
  Guard lockGuard(String orderId);

  @Insert(
      "INSERT INTO task(id,order_id,state,created_by) VALUES(#{id},#{orderId},'QUEUED',#{actor})")
  int insertTask(
      @Param("id") UUID id, @Param("orderId") String orderId, @Param("actor") String actor);

  @Insert(
      "INSERT INTO evidence(id,task_id,source,business_version,payload,fetched_at) VALUES(#{id},#{taskId},'SIMULATOR_ORDER',#{snapshot.version},CAST(#{snapshot.payload} AS jsonb),#{snapshot.fetchedAt})")
  int insertEvidence(
      @Param("id") UUID id, @Param("taskId") UUID taskId, @Param("snapshot") Snapshot snapshot);

  @Insert(
      "INSERT INTO audit_event(task_id,actor,event_type,correlation_id) VALUES(#{taskId},#{actor},#{type},#{correlationId})")
  int audit(
      @Param("taskId") UUID taskId,
      @Param("actor") String actor,
      @Param("type") AuditType type,
      @Param("correlationId") UUID correlationId);

  @Select(
      "SELECT id,task_id,source,business_version,payload::text AS payload,fetched_at FROM evidence WHERE task_id=#{id} ORDER BY fetched_at,id")
  List<EvidenceRow> evidence(UUID id);

  @Select(
      "SELECT * FROM audit_event WHERE task_id=#{taskId} AND id>#{after} ORDER BY id LIMIT #{limit}")
  List<AuditRow> auditPage(
      @Param("taskId") UUID taskId, @Param("after") long after, @Param("limit") int limit);

  @Select(
      """
      <script>
      SELECT * FROM task WHERE left(order_id,length(#{prefix}))=#{prefix}
      <if test='orderId != null'> AND order_id=#{orderId}</if>
      <if test='state != null'> AND state=#{state}</if>
      ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}
      </script>
      """)
  List<TaskRow> list(
      @Param("prefix") String prefix,
      @Param("orderId") String orderId,
      @Param("state") State state,
      @Param("size") int size,
      @Param("offset") long offset);

  @Select(
      """
      <script>
      SELECT count(*) FROM task WHERE left(order_id,length(#{prefix}))=#{prefix}
      <if test='orderId != null'> AND order_id=#{orderId}</if>
      <if test='state != null'> AND state=#{state}</if>
      </script>
      """)
  long count(
      @Param("prefix") String prefix,
      @Param("orderId") String orderId,
      @Param("state") State state);
}
