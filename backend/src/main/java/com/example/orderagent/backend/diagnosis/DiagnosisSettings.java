package com.example.orderagent.backend.diagnosis;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class DiagnosisSettings {
  public final boolean enabled, stub;
  public final int rounds, tools, repeats, reviews;
  public final long timeoutMs, totalMs, reviewMs;

  public DiagnosisSettings(Environment env) {
    String mode = env.getProperty("agent.diagnosis.mode", "agent");
    if (!mode.equals("agent") && !mode.equals("legacy-test"))
      throw new IllegalArgumentException("诊断模式无效");
    enabled = mode.equals("agent");
    stub = env.getProperty("agent.model.mode", "stub").equals("stub");
    rounds = (int) value(env, "rounds", 6, 1, 12);
    tools = (int) value(env, "tools", 12, 1, 24);
    repeats = (int) value(env, "repeats", 2, 1, 3);
    reviews = (int) value(env, "reviews", 1, 0, 2);
    timeoutMs = value(env, "timeout-ms", 30000, 100, 30000);
    totalMs = value(env, "total-ms", 120000, 1000, 300000);
    reviewMs = value(env, "review-ms", 10000, 100, 60000);
  }

  private long value(Environment e, String key, long fallback, long min, long max) {
    long v = e.getProperty("agent.diagnosis." + key, Long.class, fallback);
    if (v < min || v > max) throw new IllegalArgumentException("诊断预算无效：" + key);
    return v;
  }
}
