package com.example.orderagent.simulator.recovery;

import com.example.orderagent.simulator.recovery.ProbeProtocol.*;
import org.apache.ibatis.annotations.*;

// 实验通过显式 MyBatis 配置注册；普通服务不扫描此 Mapper。
public interface ProbeDownstreamMapper {
  @Insert(
      """
      INSERT INTO t04_probe.orders(order_id,status,payment_id,amount_minor,currency,payment_status,payment_amount_minor,payment_currency,version)
      VALUES(#{id},'PENDING_PAYMENT',#{paymentId},10000,'CNY','SUCCESS',10000,'CNY',1)
      ON CONFLICT(order_id) DO NOTHING
      """)
  int seed(@Param("id") String id, @Param("paymentId") String paymentId);

  @Insert(
      """
      INSERT INTO t04_probe.operations(operation_id,request_hash,request_json,status,code,before_version,after_version)
      VALUES(#{id},#{hash},CAST(#{json} AS jsonb),'IN_PROGRESS','NONE',0,0)
      ON CONFLICT(operation_id) DO NOTHING
      """)
  int reserve(@Param("id") String id, @Param("hash") String hash, @Param("json") String json);

  @Select(
      "SELECT operation_id,request_hash,request_json::text AS request_json,status,code,before_version,after_version FROM t04_probe.operations WHERE operation_id=#{id} FOR UPDATE")
  StoredOperation lockOperation(String id);

  @Select(
      "SELECT operation_id,request_hash,request_json::text AS request_json,status,code,before_version,after_version FROM t04_probe.operations WHERE operation_id=#{id}")
  StoredOperation findOperation(String id);

  @Select(
      "SELECT order_id,status,payment_id,amount_minor,currency,payment_status,payment_amount_minor,payment_currency,version,effect_count FROM t04_probe.orders WHERE order_id=#{id} FOR UPDATE")
  Order lockOrder(String id);

  @Select(
      "SELECT order_id,status,payment_id,amount_minor,currency,payment_status,payment_amount_minor,payment_currency,version,effect_count FROM t04_probe.orders WHERE order_id=#{id}")
  Order findOrder(String id);

  @Update(
      "UPDATE t04_probe.orders SET status='PAID',version=version+1,effect_count=effect_count+1 WHERE order_id=#{id} AND status='PENDING_PAYMENT' AND version=#{version}")
  int advance(@Param("id") String id, @Param("version") long version);

  @Update(
      "UPDATE t04_probe.operations SET status=#{status},code=#{code},before_version=#{before},after_version=#{after},completed_at=clock_timestamp() WHERE operation_id=#{id}")
  int complete(
      @Param("id") String id,
      @Param("status") ResultStatus status,
      @Param("code") String code,
      @Param("before") long before,
      @Param("after") long after);
}
