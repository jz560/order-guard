package com.example.orderagent.backend.foundation;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DatabaseIdentityMapper {
  @Select("SELECT service_name FROM service_identity WHERE singleton = TRUE")
  String serviceName();
}
