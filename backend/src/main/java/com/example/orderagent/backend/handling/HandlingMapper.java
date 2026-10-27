package com.example.orderagent.backend.handling;

import com.example.orderagent.backend.handling.HandlingRecords.*;
import com.example.orderagent.backend.task.TaskRecords.State;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface HandlingMapper {
  @Select(
      "SELECT * FROM task_handling WHERE task_id=#{id} AND action=#{action} AND expected_version=#{version}")
  HandlingRecords.Record prior(
      @Param("id") UUID id, @Param("action") Action action, @Param("version") long version);

  @Select("SELECT * FROM task_handling WHERE task_id=#{id} ORDER BY created_at,id LIMIT 100")
  List<HandlingRecords.Record> history(UUID id);

  @Insert(
      """
      INSERT INTO task_handling(id,task_id,action,expected_version,actor,reason,resolution,outcome,operation_id,evidence_id)
      VALUES(#{id},#{taskId},#{action},#{expectedVersion},#{actor},#{reason},#{resolution},#{outcome},#{operationId},#{evidenceId})
      """)
  int insert(HandlingRecords.Record record);

  @Update(
      """
      UPDATE task SET state=#{state},outcome=#{outcome},version=version+1,lease_epoch=lease_epoch+1,
        lease_owner=NULL,lease_until=NULL,next_run_at=NULL,updated_at=clock_timestamp()
      WHERE id=#{id} AND version=#{version} AND state=#{before}
      """)
  int transition(
      @Param("id") UUID id,
      @Param("version") long version,
      @Param("before") State before,
      @Param("state") State state,
      @Param("outcome") String outcome);
}
