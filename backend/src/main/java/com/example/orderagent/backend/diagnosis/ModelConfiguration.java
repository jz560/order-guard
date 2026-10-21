package com.example.orderagent.backend.diagnosis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ModelConfiguration {
  @Bean
  ModelGateway modelGateway(@Value("${agent.model.mode:stub}") String mode) {
    if (!"stub".equals(mode)) {
      throw new IllegalArgumentException("T01 仅支持 MODEL_MODE=stub；真实模型接入在 T02 实现");
    }
    return new StubModelGateway();
  }
}
