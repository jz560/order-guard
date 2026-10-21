package com.example.orderagent.backend.diagnosis;

import java.time.Duration;
import java.util.Map;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/** 通过 Spring AI 的兼容协议适配 DeepSeek；直接 ChatModel 调用不自动执行工具。 */
public final class DeepSeekModelGateway implements ModelGateway {
  private final OpenAiChatModel model;

  public DeepSeekModelGateway(String apiKey) {
    this(apiKey, "https://api.deepseek.com", Duration.ofSeconds(30));
  }

  // 包内仅用于本地 HTTP 协议测试；生产入口固定官方地址，避免凭证被任意转发。
  DeepSeekModelGateway(String apiKey, String baseUrl, Duration timeout) {
    if (apiKey == null || apiKey.isBlank())
      throw new IllegalArgumentException("缺少 DEEPSEEK_API_KEY");
    model =
        OpenAiChatModel.builder()
            .options(
                OpenAiChatOptions.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .model("deepseek-flash")
                    .timeout(timeout)
                    .maxRetries(0)
                    .maxTokens(1000)
                    .extraBody(Map.of("thinking", Map.of("type", "disabled")))
                    .build())
            .build();
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    // Spring AI 2.0.0 适配器要求供应商选项类型；应用层保持通用工具契约。
    var options = model.getOptions().mutate();
    if (prompt.getOptions()
        instanceof org.springframework.ai.model.tool.ToolCallingChatOptions tools) {
      options.toolCallbacks(tools.getToolCallbacks());
    }
    return model.call(new Prompt(prompt.getInstructions(), options.build()));
  }
}
