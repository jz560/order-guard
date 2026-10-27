package com.example.orderagent.backend.execution;

import com.example.orderagent.backend.execution.ExecutionRecords.*;
import com.example.orderagent.backend.worker.WorkerRecords.Claim;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ExecutionMapper {
  @Select(
      value =
          """
      WITH candidate AS (
        SELECT id FROM task WHERE state IN ('APPROVED','EXECUTING','UNKNOWN','VERIFYING')
        AND (next_run_at<=clock_timestamp() OR (state='APPROVED' AND next_run_at IS NULL))
        AND (lease_until IS NULL OR lease_until<=clock_timestamp())
        ORDER BY next_run_at NULLS FIRST,id FOR UPDATE SKIP LOCKED LIMIT 1)
      UPDATE task t SET lease_owner=#{owner},lease_until=clock_timestamp()+#{leaseMillis}*interval '1 millisecond',
        lease_epoch=t.lease_epoch+1,version=t.version+1,updated_at=clock_timestamp()
      FROM candidate c WHERE t.id=c.id
      RETURNING t.id AS task_id,t.order_id,t.state,t.version,t.lease_owner,t.lease_epoch,t.lease_until,t.worker_failures,t.updated_at AS claimed_at
      """,
      affectData = true)
  Claim claim(@Param("owner") String owner, @Param("leaseMillis") long leaseMillis);

  @Select(
      value =
          """
      UPDATE task SET state='EXECUTING',version=version+1,updated_at=clock_timestamp(),next_run_at=clock_timestamp()
      WHERE id=#{c.taskId} AND state='APPROVED' AND version=#{c.version} AND lease_owner=#{c.leaseOwner}
        AND lease_epoch=#{c.leaseEpoch} AND lease_until>clock_timestamp()
        AND EXISTS(SELECT 1 FROM plan WHERE id=#{planId} AND expires_at>clock_timestamp())
      RETURNING id AS task_id,order_id,state,version,lease_owner,lease_epoch,lease_until,worker_failures,#{c.claimedAt}::timestamptz AS claimed_at
      """,
      affectData = true)
  Claim start(@Param("c") Claim claim, @Param("planId") UUID planId);

  @Select(
      """
      SELECT operation_id,task_id,plan_id,params_hash,status,checks,result::text AS result,last_error,created_at,deadline_at,updated_at
      FROM execution WHERE task_id=#{taskId}
      """)
  ExecutionRow execution(UUID taskId);

  @Insert(
      """
      INSERT INTO execution(operation_id,task_id,plan_id,params_hash,status)
      VALUES(#{id},#{taskId},#{planId},#{hash},'EXECUTING')
      """)
  int insert(
      @Param("id") UUID id,
      @Param("taskId") UUID taskId,
      @Param("planId") UUID planId,
      @Param("hash") String hash);

  @Update(
      """
      UPDATE execution SET status=#{status},checks=checks+1,result=COALESCE(CAST(#{result} AS jsonb),result),
        last_error=#{error},updated_at=clock_timestamp() WHERE task_id=#{taskId}
      """)
  int update(
      @Param("taskId") UUID taskId,
      @Param("status") Status status,
      @Param("result") String result,
      @Param("error") String error);

  @Insert(
      """
      INSERT INTO evidence(id,task_id,source,business_version,payload,fetched_at)
      VALUES(#{id},#{taskId},#{source},#{version},CAST(#{payload} AS jsonb),#{at})
      """)
  int evidence(
      @Param("id") UUID id,
      @Param("taskId") UUID taskId,
      @Param("source") String source,
      @Param("version") long version,
      @Param("payload") String payload,
      @Param("at") Instant at);
}
