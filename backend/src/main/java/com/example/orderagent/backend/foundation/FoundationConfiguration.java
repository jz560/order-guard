package com.example.orderagent.backend.foundation;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

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

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            auth -> auth.requestMatchers("/actuator/health").permitAll().anyRequest().denyAll())
        .build();
  }
}
