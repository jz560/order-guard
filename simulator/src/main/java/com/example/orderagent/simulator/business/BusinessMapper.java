package com.example.orderagent.simulator.business;

import com.example.orderagent.simulator.business.BusinessTypes.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface BusinessMapper {
  @Select("SELECT * FROM orders WHERE id=#{id}")
  Order order(String id);

  @Select("SELECT * FROM orders WHERE id=#{id} FOR UPDATE")
  Order lockOrder(String id);

  @Select("SELECT * FROM payments WHERE id=#{id}")
  Payment payment(String id);

  @Select("SELECT * FROM payments WHERE id=#{id} FOR UPDATE")
  Payment lockPayment(String id);

  @Select("SELECT * FROM callbacks WHERE payment_id=#{id} ORDER BY id")
  List<Callback> callbacks(String id);

  @Select("SELECT * FROM simulation_scenarios ORDER BY scenario")
  List<ScenarioState> scenarios();

  @Select("SELECT * FROM simulation_scenarios WHERE scenario=#{scenario} FOR UPDATE")
  ScenarioState lockScenario(Scenario scenario);

  @Select("SELECT * FROM simulation_scenarios WHERE scenario=#{scenario}")
  ScenarioState scenario(Scenario scenario);

  @Insert("INSERT INTO simulation_scenarios(scenario) VALUES(#{scenario}) ON CONFLICT DO NOTHING")
  int seedScenario(Scenario scenario);

  @Update(
      "UPDATE simulation_scenarios SET active_order_id=#{id},fault='NONE' WHERE scenario=#{scenario}")
  int activate(@Param("scenario") Scenario scenario, @Param("id") String id);

  @Update("UPDATE simulation_scenarios SET fault=#{fault} WHERE scenario=#{scenario}")
  int fault(@Param("scenario") Scenario scenario, @Param("fault") Fault fault);

  @Insert(
      "INSERT INTO orders(id,scenario,status,amount_minor,currency,payment_id,version) VALUES(#{id},#{scenario},#{status},#{amountMinor},#{currency},#{paymentId},#{version})")
  int insertOrder(Order order);

  @Insert(
      "INSERT INTO payments(id,order_id,status,amount_minor,currency,paid_at) VALUES(#{id},#{orderId},#{status},#{amountMinor},#{currency},#{paidAt})")
  int insertPayment(Payment payment);

  @Insert(
      "INSERT INTO callbacks(id,payment_id,received_at,result,error_code) VALUES(#{id},#{paymentId},#{receivedAt},#{result},#{errorCode})")
  int insertCallback(Callback callback);

  @Insert(
      """
      INSERT INTO operations(operation_id,order_id,payment_id,amount_minor,currency,expected_version,action,status,code)
      VALUES(#{id},#{request.orderId},#{request.paymentId},#{request.amountMinor},#{request.currency},#{request.expectedVersion},#{request.action},'IN_PROGRESS','NONE')
      ON CONFLICT(operation_id) DO NOTHING
      """)
  int reserve(@Param("id") String id, @Param("request") RepairRequest request);

  @Select("SELECT * FROM operations WHERE operation_id=#{id} FOR UPDATE")
  Operation lockOperation(String id);

  @Select("SELECT * FROM operations WHERE operation_id=#{id}")
  Operation operation(String id);

  @Update(
      "UPDATE orders SET status='PAID',version=version+1 WHERE id=#{id} AND version=#{version} AND status='PENDING_PAYMENT'")
  int advance(@Param("id") String id, @Param("version") long version);

  @Update(
      """
      UPDATE operations SET status=#{status},code=#{code},before_version=#{before},after_version=#{after},completed_at=clock_timestamp()
      WHERE operation_id=#{id}
      """)
  int complete(
      @Param("id") String id,
      @Param("status") ResultStatus status,
      @Param("code") String code,
      @Param("before") long before,
      @Param("after") long after);
}
