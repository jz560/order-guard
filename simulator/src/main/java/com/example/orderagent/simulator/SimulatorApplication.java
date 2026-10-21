package com.example.orderagent.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class SimulatorApplication {
  public static void main(String[] args) {
    SpringApplication.run(SimulatorApplication.class, args);
  }
}
