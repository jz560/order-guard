package com.example.orderagent.backend.diagnosis;

import com.example.orderagent.backend.worker.WorkerRecords.Claim;
import java.time.Instant;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DiagnosisJournal {
  record Run(
      UUID taskId,
      int rounds,
      int tools,
      int reviews,
      Instant startedAt,
      Instant deadlineAt,
      String result) {}

  record Event(long id, UUID taskId, long epoch, String type, String payload, Instant createdAt) {}

  @Select(
      "SELECT task_id,rounds,tools,reviews,started_at,deadline_at,result::text FROM diagnosis_run WHERE task_id=#{id}")
  Run run(UUID id);

  @Insert(
      "INSERT INTO diagnosis_run(task_id,deadline_at) VALUES(#{id},clock_timestamp()+#{millis}*interval '1 millisecond') ON CONFLICT DO NOTHING")
  int init(@Param("id") UUID id, @Param("millis") long millis);

  @Select(
      "SELECT count(*) FROM task WHERE id=#{taskId} AND version=#{version} AND lease_owner=#{leaseOwner} AND lease_epoch=#{leaseEpoch} AND lease_until>clock_timestamp() AND state IN ('QUEUED','DIAGNOSING')")
  int owned(Claim claim);

  @Update("UPDATE diagnosis_run SET rounds=rounds+1 WHERE task_id=#{id}")
  int round(UUID id);

  @Update("UPDATE diagnosis_run SET tools=tools+1 WHERE task_id=#{id}")
  int tool(UUID id);

  @Update("UPDATE diagnosis_run SET reviews=reviews+1 WHERE task_id=#{id}")
  int review(UUID id);

  @Update("UPDATE diagnosis_run SET result=CAST(#{result} AS jsonb) WHERE task_id=#{id}")
  int result(@Param("id") UUID id, @Param("result") String result);

  @Insert(
      "INSERT INTO diagnosis_event(task_id,epoch,type,payload) VALUES(#{claim.taskId},#{claim.leaseEpoch},#{type},CAST(#{payload} AS jsonb))")
  int event(
      @Param("claim") Claim claim, @Param("type") String type, @Param("payload") String payload);

  @Select(
      "SELECT id,task_id,epoch,type,payload::text,created_at FROM diagnosis_event WHERE task_id=#{id} ORDER BY id")
  List<Event> events(UUID id);

  @Select(
      "SELECT count(*) FROM diagnosis_event WHERE task_id=#{id} AND type='TOOL_INTENT' AND payload->>'name'=#{name} AND (payload->>'review')::int=#{review}")
  int repeats(@Param("id") UUID id, @Param("name") String name, @Param("review") int review);

  @Select(
      "SELECT e.status::text FROM execution e JOIN task t ON t.id=e.task_id WHERE t.order_id=#{orderId} ORDER BY e.created_at DESC LIMIT 100")
  List<String> history(String orderId);

  @Insert(
      "INSERT INTO plan(id,task_id,version,action,canonical_params,params_hash,evidence_id,source) VALUES(#{id},#{task},1,'VERIFY_PAYMENT_AND_ADVANCE_ORDER',#{params},#{hash},#{evidence},'AGENT_VALIDATED')")
  int plan(
      @Param("id") UUID id,
      @Param("task") UUID task,
      @Param("params") String params,
      @Param("hash") String hash,
      @Param("evidence") UUID evidence);
}
