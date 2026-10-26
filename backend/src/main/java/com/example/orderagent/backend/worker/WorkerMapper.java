package com.example.orderagent.backend.worker;

import com.example.orderagent.backend.task.TaskRecords.State;
import com.example.orderagent.backend.worker.WorkerRecords.*;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkerMapper {
  @Select(
      value =
          """
      WITH candidate AS (
        SELECT id FROM task
        WHERE state IN ('QUEUED','DIAGNOSING') AND next_run_at <= clock_timestamp()
          AND (lease_until IS NULL OR lease_until <= clock_timestamp())
        ORDER BY next_run_at,id FOR UPDATE SKIP LOCKED LIMIT 1
      )
      UPDATE task t SET lease_owner=#{owner},lease_until=clock_timestamp()+#{leaseMillis}*interval '1 millisecond',
        lease_epoch=t.lease_epoch+1,version=t.version+1,updated_at=clock_timestamp()
      FROM candidate c WHERE t.id=c.id
      RETURNING t.id AS task_id,t.order_id,t.state,t.version,t.lease_owner,t.lease_epoch,t.lease_until,t.worker_failures,t.updated_at AS claimed_at
      """,
      affectData = true)
  Claim claim(@Param("owner") String owner, @Param("leaseMillis") long leaseMillis);

  @Update(
      """
      UPDATE task SET state=#{nextState},outcome=#{outcome},version=version+1,updated_at=clock_timestamp(),
        lease_owner=NULL,lease_until=NULL,worker_failures=#{failures},
        next_run_at=CASE WHEN #{scheduled} THEN clock_timestamp()+#{delayMillis}*interval '1 millisecond' ELSE NULL END
      WHERE id=#{claim.taskId} AND state=#{claim.state} AND version=#{claim.version}
        AND lease_owner=#{claim.leaseOwner} AND lease_epoch=#{claim.leaseEpoch} AND lease_until > clock_timestamp()
      """)
  int finish(
      @Param("claim") Claim claim,
      @Param("nextState") State nextState,
      @Param("outcome") String outcome,
      @Param("failures") int failures,
      @Param("scheduled") boolean scheduled,
      @Param("delayMillis") long delayMillis);

  @Insert(
      """
      INSERT INTO task_step(task_id,seq,type,status,before_state,after_state,output,started_at)
      VALUES(#{claim.taskId},#{claim.leaseEpoch},#{type},#{status},#{claim.state},#{after},#{output},#{claim.claimedAt})
      """)
  int step(
      @Param("claim") Claim claim,
      @Param("type") String type,
      @Param("status") String status,
      @Param("after") State after,
      @Param("output") String output);

  @Select("SELECT * FROM task_step WHERE task_id=#{id} AND id>#{after} ORDER BY id LIMIT #{limit}")
  List<StepRow> steps(@Param("id") UUID id, @Param("after") long after, @Param("limit") int limit);
}
