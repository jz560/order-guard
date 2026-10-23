package com.example.orderagent.backend.recovery;

import com.example.orderagent.backend.recovery.ProbeProtocol.*;
import org.apache.ibatis.annotations.*;

// 不加 @Mapper，避免普通 Spring 服务扫描并启用实验组件。
public interface ProbeIntentMapper {
  @Insert(
      """
      INSERT INTO t04_probe.intent(operation_id,order_id,payment_id,amount_minor,currency,expected_version,action,status,last_error)
      VALUES(#{id},#{r.orderId},#{r.paymentId},#{r.amountMinor},#{r.currency},#{r.expectedVersion},#{r.action},'PREPARED','NONE')
      ON CONFLICT(operation_id) DO NOTHING
      """)
  int insert(@Param("id") String id, @Param("r") Request request);

  @Select(
      "SELECT operation_id,order_id,payment_id,amount_minor,currency,expected_version,action,status,last_error FROM t04_probe.intent WHERE operation_id=#{id}")
  Intent find(String id);

  @Update(
      "UPDATE t04_probe.intent SET status='IN_FLIGHT',updated_at=clock_timestamp() WHERE operation_id=#{id} AND status='PREPARED'")
  int claim(String id);

  @Update(
      """
      UPDATE t04_probe.intent SET status=#{state},last_error=#{code},updated_at=clock_timestamp()
      WHERE operation_id=#{id} AND status IN ('PREPARED','IN_FLIGHT','UNKNOWN')
      """)
  int finish(@Param("id") String id, @Param("state") State state, @Param("code") String code);
}
