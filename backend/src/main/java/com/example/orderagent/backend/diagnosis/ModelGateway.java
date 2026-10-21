package com.example.orderagent.backend.diagnosis;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/** 一轮模型调用边界；工具循环与预算控制由后续应用层实现。 */
public interface ModelGateway {
  ChatResponse call(Prompt prompt);
}
