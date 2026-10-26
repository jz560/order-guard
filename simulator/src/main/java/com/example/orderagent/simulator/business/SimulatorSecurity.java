package com.example.orderagent.simulator.business;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
public class SimulatorSecurity {
  @Bean
  ApplicationRunner initializeScenarios(ScenarioService service) {
    return args -> service.initialize();
  }

  @Bean
  SecurityFilterChain simulatorFilterChain(HttpSecurity http, Environment env, DemoMode mode)
      throws Exception {
    Map<String, String> credentials = new LinkedHashMap<>();
    for (String role : List.of("READ", "EXECUTE", "ADMIN")) {
      String token =
          env.getProperty("simulator.auth." + role.toLowerCase(Locale.ROOT) + "-token", "");
      if (!token.isBlank()) {
        if (token.length() < 16 || credentials.putIfAbsent(token, role) != null)
          throw new IllegalArgumentException("模拟服务凭证至少 16 个字符且三种凭证必须不同");
      }
    }
    var filter =
        new OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(
              HttpServletRequest request, HttpServletResponse response, FilterChain chain)
              throws ServletException, IOException {
            response.setHeader("X-Correlation-ID", ApiErrors.correlation(request));
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
              byte[] candidate = header.substring(7).getBytes(StandardCharsets.UTF_8);
              for (var entry : credentials.entrySet()) {
                if (MessageDigest.isEqual(
                    candidate, entry.getKey().getBytes(StandardCharsets.UTF_8))) {
                  var auth =
                      new UsernamePasswordAuthenticationToken(
                          "simulator-" + entry.getValue(),
                          null,
                          List.of(new SimpleGrantedAuthority("ROLE_" + entry.getValue())));
                  SecurityContextHolder.getContext().setAuthentication(auth);
                }
              }
            }
            chain.doFilter(request, response);
          }
        };
    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(c -> c.disable())
        .addFilterBefore(filter, AnonymousAuthenticationFilter.class)
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint((req, res, ex) -> ApiErrors.forbidden(req, res))
                    .accessDeniedHandler((req, res, ex) -> ApiErrors.forbidden(req, res)))
        .authorizeHttpRequests(
            auth -> {
              auth.requestMatchers("/actuator/health").permitAll();
              if (mode.enabled()) auth.requestMatchers("/internal/demo/**").hasRole("ADMIN");
              else auth.requestMatchers("/internal/demo/**").denyAll();
              auth.requestMatchers(
                      HttpMethod.GET,
                      "/internal/orders/*",
                      "/internal/payments/*",
                      "/internal/payments/*/callbacks",
                      "/internal/operations/*")
                  .hasAnyRole("READ", "EXECUTE")
                  .requestMatchers(HttpMethod.PUT, "/internal/operations/*")
                  .hasRole("EXECUTE")
                  .anyRequest()
                  .denyAll();
            })
        .build();
  }
}
