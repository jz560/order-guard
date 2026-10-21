package com.example.orderagent.backend.diagnosis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ModelConfiguration {
  @Bean
  ModelGateway modelGateway(
      @Value("${agent.model.mode:stub}") String mode,
      @Value("${DEEPSEEK_API_KEY:}") String apiKey) {
    return switch (mode) {
      case "stub" -> new StubModelGateway();
      case "deepseek" -> new DeepSeekModelGateway(apiKey);
      default -> throw new IllegalArgumentException("MODEL_MODE 仅支持 stub 或 deepseek");
    };
  }
}
