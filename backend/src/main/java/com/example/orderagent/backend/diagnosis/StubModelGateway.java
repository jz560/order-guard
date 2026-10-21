package com.example.orderagent.backend.diagnosis;

import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/** 无网络、无费用的工程替身，不代表真实模型诊断效果。 */
public final class StubModelGateway implements ModelGateway {
  @Override
  public ChatResponse call(Prompt prompt) {
    Objects.requireNonNull(prompt, "prompt");
    return new ChatResponse(
        List.of(
            new Generation(
                new AssistantMessage(
                    "{\"source\":\"stub\",\"recommendation\":\"MANUAL\",\"reason\":\"T01 测试替身，未执行真实诊断\",\"evidenceIds\":[]}"))));
  }
}
