package com.example.orderagent.backend.worker;

import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class WorkerSettings {
  private final boolean enabled;
  private final long leaseMillis, stepDelayMillis, retryMillis;
  private final int maxFailures;

  public WorkerSettings(Environment env) {
    var active = env.getActiveProfiles();
    var profiles = active.length == 0 ? env.getDefaultProfiles() : active;
    boolean demo =
        profiles.length > 0
            && Arrays.stream(profiles).allMatch(p -> p.equals("demo") || p.equals("test"));
    enabled = env.getProperty("agent.worker.enabled", Boolean.class, true) && demo;
    leaseMillis = bounded(env, "lease-ms", 30000, 1000, 300000);
    stepDelayMillis = bounded(env, "step-delay-ms", 1000, 0, 60000);
    retryMillis = bounded(env, "retry-ms", 1000, 100, 60000);
    maxFailures = (int) bounded(env, "max-failures", 3, 1, 10);
    bounded(env, "poll-ms", 500, 50, 60000);
  }

  private static long bounded(Environment env, String key, long fallback, long min, long max) {
    long value = env.getProperty("agent.worker." + key, Long.class, fallback);
    if (value < min || value > max) throw new IllegalArgumentException("Worker 配置超出范围：" + key);
    return value;
  }

  public boolean enabled() {
    return enabled;
  }

  public long leaseMillis() {
    return leaseMillis;
  }

  public long stepDelayMillis() {
    return stepDelayMillis;
  }

  public long retryMillis() {
    return retryMillis;
  }

  public int maxFailures() {
    return maxFailures;
  }
}
