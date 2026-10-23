package com.example.orderagent.simulator.recovery;

import com.example.orderagent.simulator.recovery.ProbeProtocol.*;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;

public final class ProbeDownstreamStore {
  private final SqlSessionFactory sessions;

  public ProbeDownstreamStore(String url, String user, String password) {
    var source = new UnpooledDataSource("org.postgresql.Driver", url, user, password);
    Flyway.configure()
        .dataSource(source)
        .schemas("t04_probe")
        .defaultSchema("t04_probe")
        .locations("classpath:db/t04-simulator")
        .cleanDisabled(true)
        .load()
        .migrate();
    var config = new Configuration(new Environment("t04", new JdbcTransactionFactory(), source));
    config.setMapUnderscoreToCamelCase(true);
    config.setArgNameBasedConstructorAutoMapping(true);
    config.addMapper(ProbeDownstreamMapper.class);
    sessions = new SqlSessionFactoryBuilder().build(config);
  }

  public void seed(String orderId) {
    new Request(orderId, "T04-PAY-" + orderId, 10000, "CNY", 1, "VERIFY_PAYMENT_AND_ADVANCE_ORDER");
    try (var session = sessions.openSession(false)) {
      session.getMapper(ProbeDownstreamMapper.class).seed(orderId, "T04-PAY-" + orderId);
      session.commit();
    }
  }

  public Operation execute(String operationId, Request request) {
    try (var session = sessions.openSession(false)) {
      var mapper = session.getMapper(ProbeDownstreamMapper.class);
      mapper.reserve(operationId, request.hash(), ProbeProtocol.JSON.writeValueAsString(request));
      var stored = mapper.lockOperation(operationId);
      if (!stored.requestHash().equals(request.hash())
          || !ProbeProtocol.JSON.readValue(stored.requestJson(), Request.class).equals(request))
        throw new Conflict();
      if (stored.status() != ResultStatus.IN_PROGRESS) {
        session.commit();
        return stored.response();
      }
      var order = mapper.lockOrder(request.orderId());
      boolean valid =
          order != null
              && "PENDING_PAYMENT".equals(order.status())
              && "SUCCESS".equals(order.paymentStatus())
              && request.paymentId().equals(order.paymentId())
              && request.amountMinor() == order.amountMinor()
              && request.amountMinor() == order.paymentAmountMinor()
              && request.currency().equals(order.currency())
              && request.currency().equals(order.paymentCurrency())
              && request.expectedVersion() == order.version();
      long before = order == null ? 0 : order.version(), after = before;
      ResultStatus status = ResultStatus.REJECTED;
      String code = "BUSINESS_PRECONDITION_FAILED";
      if (valid) {
        if (mapper.advance(request.orderId(), request.expectedVersion()) != 1)
          throw new IllegalStateException("CONCURRENT_ORDER_CHANGE");
        status = ResultStatus.SUCCEEDED;
        code = "VERIFIED_AND_ADVANCED";
        after = before + 1;
      }
      mapper.complete(operationId, status, code, before, after);
      var result = mapper.findOperation(operationId).response();
      // 业务变更和结果记录到此一并提交；后续响应故障发生在此方法返回后。
      session.commit();
      return result;
    }
  }

  public Operation operation(String id) {
    try (var session = sessions.openSession()) {
      var row = session.getMapper(ProbeDownstreamMapper.class).findOperation(id);
      return row == null ? null : row.response();
    }
  }

  public Order order(String id) {
    try (var session = sessions.openSession()) {
      return session.getMapper(ProbeDownstreamMapper.class).findOrder(id);
    }
  }
}
