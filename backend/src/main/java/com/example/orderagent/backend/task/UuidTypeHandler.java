package com.example.orderagent.backend.task;

import java.sql.*;
import java.util.UUID;
import org.apache.ibatis.type.*;

@MappedTypes(UUID.class)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {
  @Override
  public void setNonNullParameter(PreparedStatement statement, int index, UUID value, JdbcType type)
      throws SQLException {
    statement.setObject(index, value);
  }

  @Override
  public UUID getNullableResult(ResultSet result, String column) throws SQLException {
    return result.getObject(column, UUID.class);
  }

  @Override
  public UUID getNullableResult(ResultSet result, int column) throws SQLException {
    return result.getObject(column, UUID.class);
  }

  @Override
  public UUID getNullableResult(CallableStatement statement, int column) throws SQLException {
    return statement.getObject(column, UUID.class);
  }
}
