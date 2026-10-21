package com.example.orderagent.backend.diagnosis;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/** 一轮模型调用边界；工具执行与预算控制由应用层负责。 */
public interface ModelGateway {
  ChatResponse call(Prompt prompt);
}
