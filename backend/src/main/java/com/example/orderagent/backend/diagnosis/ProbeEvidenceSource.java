package com.example.orderagent.backend.diagnosis;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/** 只读实验证据边界；绑定范围来自实验程序，不来自模型参数。 */
public interface ProbeEvidenceSource {
  String orderId();

  String source();

  List<ToolCallback> definitions();

  String execute(String name, String arguments, String boundOrderId);
}
