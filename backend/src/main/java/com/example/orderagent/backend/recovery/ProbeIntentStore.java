package com.example.orderagent.backend.recovery;

import com.example.orderagent.backend.recovery.ProbeProtocol.*;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;

public final class ProbeIntentStore {
  private final SqlSessionFactory sessions;

  public ProbeIntentStore(String url, String user, String password) {
    var datasource = new UnpooledDataSource("org.postgresql.Driver", url, user, password);
    Flyway.configure()
        .dataSource(datasource)
        .schemas("t04_probe")
        .defaultSchema("t04_probe")
        .locations("classpath:db/t04-caller")
        .cleanDisabled(true)
        .load()
        .migrate();
    var config =
        new Configuration(new Environment("t04", new JdbcTransactionFactory(), datasource));
    config.setMapUnderscoreToCamelCase(true);
    config.setArgNameBasedConstructorAutoMapping(true);
    config.addMapper(ProbeIntentMapper.class);
    sessions = new SqlSessionFactoryBuilder().build(config);
  }

  public Intent prepare(String id, Request request) {
    java.util.UUID.fromString(id);
    try (var session = sessions.openSession(false)) {
      var mapper = session.getMapper(ProbeIntentMapper.class);
      mapper.insert(id, request);
      var intent = mapper.find(id);
      if (!intent.request().equals(request))
        throw new IllegalArgumentException("OPERATION_ID_CONFLICT");
      session.commit();
      return intent;
    }
  }

  public Intent find(String id) {
    try (var session = sessions.openSession()) {
      return session.getMapper(ProbeIntentMapper.class).find(id);
    }
  }

  public boolean claim(String id) {
    try (var session = sessions.openSession(false)) {
      int rows = session.getMapper(ProbeIntentMapper.class).claim(id);
      session.commit();
      return rows == 1;
    }
  }

  public Intent finish(String id, State state, String code) {
    try (var session = sessions.openSession(false)) {
      var mapper = session.getMapper(ProbeIntentMapper.class);
      mapper.finish(id, state, code);
      session.commit();
      return mapper.find(id);
    }
  }
}
