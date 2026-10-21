package com.example.orderagent.backend.diagnosis;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/** 无 HTTP 入口、无数据库访问的显式命令行实验。 */
public final class T02Probe {
  public static void main(String[] args) throws Exception {
    if (args.length != 1 || (!args[0].equals("stub") && !args[0].equals("deepseek")))
      throw new IllegalArgumentException("用法：T02Probe stub|deepseek");
    boolean live = args[0].equals("deepseek");
    if (live && !"true".equals(System.getenv("T02_ALLOW_LIVE")))
      throw new IllegalArgumentException("真实调用需要显式设置 T02_ALLOW_LIVE=true");
    ModelGateway gateway =
        live ? new DeepSeekModelGateway(System.getenv("DEEPSEEK_API_KEY")) : scripted();
    var trace = new ProbeTrace(Path.of(".cache", "t02-runs"));
    var result =
        new ProbeLoop(gateway, new ProbeTools(), ProbeLoop.Limits.defaults(), trace)
            .run(ProbeTools.ORDER_ID);
    System.out.println(
        "mode="
            + args[0]
            + ", status="
            + result.status()
            + ", reason="
            + result.reason()
            + ", modelCalls="
            + result.modelCalls()
            + ", toolCalls="
            + result.toolCalls());
    System.out.println("trace=" + trace.file().toAbsolutePath());
    if (!"COMPLETED".equals(result.status())) System.exit(2);
  }

  static ModelGateway scripted() {
    var round = new AtomicInteger();
    return prompt -> {
      AssistantMessage message;
      if (round.incrementAndGet() == 1) {
        var calls =
            List.of("query_order", "query_payment", "query_callbacks", "query_history").stream()
                .map(
                    name ->
                        new AssistantMessage.ToolCall(
                            name,
                            "function",
                            name,
                            "{\"orderId\":\"" + ProbeTools.ORDER_ID + "\"}"))
                .toList();
        message = AssistantMessage.builder().content("").toolCalls(calls).build();
      } else {
        message =
            new AssistantMessage(
                "{\"recommendation\":\"RECOMMEND_RECHECK\",\"reason\":\"模拟支付成功、订单待支付且回调失败；仅建议核验\",\"evidenceIds\":[\"query_order\",\"query_payment\",\"query_callbacks\",\"query_history\"]}");
      }
      return new ChatResponse(List.of(new Generation(message)));
    };
  }
}
