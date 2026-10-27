package com.example.orderagent.backend.approval;

import com.example.orderagent.backend.approval.PlanRecords.*;
import com.example.orderagent.backend.task.TaskRecords.*;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PlanMapper {
  @Select("SELECT * FROM plan WHERE id=#{id}")
  PlanRow plan(UUID id);

  @Select("SELECT * FROM plan WHERE task_id=#{id} ORDER BY version DESC LIMIT 1")
  PlanRow latest(UUID id);

  @Select("SELECT * FROM task WHERE id=#{id} FOR UPDATE")
  TaskRow lockTask(UUID id);

  @Select("SELECT clock_timestamp()")
  Instant now();

  @Select("SELECT * FROM approval WHERE plan_id=#{id}")
  ApprovalRow approval(UUID id);

  @Insert(
      """
      INSERT INTO plan(id,task_id,version,action,canonical_params,params_hash,evidence_id,source)
      VALUES(#{id},#{taskId},1,#{action},#{params},#{hash},#{evidenceId},'TEST_STUB')
      """)
  int insert(
      @Param("id") UUID id,
      @Param("taskId") UUID taskId,
      @Param("action") Action action,
      @Param("params") String params,
      @Param("hash") String hash,
      @Param("evidenceId") UUID evidenceId);

  @Insert(
      """
      INSERT INTO approval(id,plan_id,decision,approver_id,reason)
      VALUES(#{id},#{planId},#{decision},#{actor},#{reason})
      """)
  int decide(
      @Param("id") UUID id,
      @Param("planId") UUID planId,
      @Param("decision") Decision decision,
      @Param("actor") String actor,
      @Param("reason") String reason);

  @Update(
      """
      UPDATE task SET state=#{state},outcome=#{outcome},version=version+1,
      updated_at=clock_timestamp(),next_run_at=NULL
      WHERE id=#{taskId} AND version=#{version} AND state='WAITING_APPROVAL'
      AND EXISTS(SELECT 1 FROM plan WHERE id=#{planId} AND expires_at>clock_timestamp())
      """)
  int transition(
      @Param("taskId") UUID taskId,
      @Param("version") long version,
      @Param("planId") UUID planId,
      @Param("state") State state,
      @Param("outcome") String outcome);

  @Delete("DELETE FROM order_guard WHERE task_id=#{id}")
  int release(UUID id);

  @Select(
      """
      SELECT t.* FROM task t JOIN plan p ON p.task_id=t.id
      WHERE t.state IN ('WAITING_APPROVAL','APPROVED') AND p.expires_at<=clock_timestamp()
      AND p.version=(SELECT max(version) FROM plan WHERE task_id=t.id)
      ORDER BY p.expires_at,t.id LIMIT 1 FOR UPDATE OF t SKIP LOCKED
      """)
  TaskRow lockExpired();

  @Update(
      """
      UPDATE task SET state='FINISHED',outcome='PLAN_EXPIRED',version=version+1,
      updated_at=clock_timestamp(),next_run_at=NULL,lease_owner=NULL,lease_until=NULL
      WHERE id=#{id} AND version=#{version} AND state IN ('WAITING_APPROVAL','APPROVED')
      """)
  int expire(@Param("id") UUID id, @Param("version") long version);
}
