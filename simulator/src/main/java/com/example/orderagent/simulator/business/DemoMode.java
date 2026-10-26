package com.example.orderagent.simulator.business;

import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class DemoMode {
  private final boolean enabled;

  public DemoMode(Environment environment) {
    var active = environment.getActiveProfiles();
    var profiles = active.length == 0 ? environment.getDefaultProfiles() : active;
    enabled =
        profiles.length > 0
            && Arrays.stream(profiles).allMatch(p -> p.equals("demo") || p.equals("test"));
  }

  public boolean enabled() {
    return enabled;
  }

  public void require() {
    if (!enabled) throw new BusinessException(404, "NOT_FOUND", "此环境不开放演示管理接口");
  }
}
