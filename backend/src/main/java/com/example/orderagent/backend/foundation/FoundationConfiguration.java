package com.example.orderagent.backend.foundation;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class FoundationConfiguration {
  @Bean
  ApplicationRunner verifyDatabaseIdentity(DatabaseIdentityMapper mapper) {
    return args -> {
      if (!"backend".equals(mapper.serviceName())) {
        throw new IllegalStateException("数据库归属与当前服务不一致，拒绝启动");
      }
    };
  }
}
