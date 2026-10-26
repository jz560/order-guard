package com.example.orderagent.backend.security;

import com.example.orderagent.backend.api.ApiErrors;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
public class WorkbenchSecurity {
  @Bean
  UserDetailsService workbenchUsers(Environment env) {
    Map<String, WorkbenchAccount> accounts = new HashMap<>();
    var active = env.getActiveProfiles();
    var profiles = active.length == 0 ? env.getDefaultProfiles() : active;
    boolean demo =
        profiles.length > 0
            && Arrays.stream(profiles).allMatch(p -> p.equals("demo") || p.equals("test"));
    if (demo) {
      var encoder = new BCryptPasswordEncoder();
      for (String name : List.of("operator", "approver")) {
        String password = env.getProperty("agent.accounts." + name + ".password", "");
        String prefix = env.getProperty("agent.accounts." + name + ".order-prefix", "T05-ORDER-");
        if (!password.isBlank()) {
          if (password.length() < 12
              || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
              || !prefix.matches("[A-Za-z0-9_-]{1,100}"))
            throw new IllegalArgumentException("预置账号密码须为 12—72 字节以内且订单范围不能为空");
          accounts.put(
              name,
              new WorkbenchAccount(
                  name,
                  encoder.encode(password),
                  name.equals("operator") ? "OPERATOR" : "APPROVER",
                  prefix));
        }
      }
    }
    return username -> {
      var account = accounts.get(username);
      if (account == null) throw new UsernameNotFoundException("账号不存在");
      return account;
    };
  }

  @Bean
  SecurityFilterChain workbenchFilterChain(HttpSecurity http, UserDetailsService users)
      throws Exception {
    var provider = new DaoAuthenticationProvider(users);
    provider.setPasswordEncoder(new BCryptPasswordEncoder());
    var securityContext = new HttpSessionSecurityContextRepository();
    var correlation =
        new OncePerRequestFilter() {
          @Override
          protected void doFilterInternal(
              HttpServletRequest req, HttpServletResponse res, FilterChain chain)
              throws ServletException, IOException {
            res.setHeader("X-Correlation-ID", ApiErrors.correlation(req));
            chain.doFilter(req, res);
          }
        };
    return http.authenticationProvider(provider)
        // Basic 登录后保存认证上下文，避免无状态重复认证不断轮换 CSRF 令牌。
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .securityContext(c -> c.securityContextRepository(securityContext))
        .requestCache(c -> c.disable())
        .logout(
            l ->
                l.logoutUrl("/api/auth/logout")
                    .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
        .addFilterBefore(correlation, CsrfFilter.class)
        .httpBasic(
            b ->
                b.securityContextRepository(securityContext)
                    .authenticationEntryPoint((req, res, ex) -> ApiErrors.security(req, res, 401)))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) ->
                            ApiErrors.security(
                                req, res, req.getRequestURI().startsWith("/api/") ? 401 : 403))
                    .accessDeniedHandler((req, res, ex) -> ApiErrors.security(req, res, 403)))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/tasks")
                    .hasRole("OPERATOR")
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/me",
                        "/api/auth/csrf",
                        "/api/tasks",
                        "/api/tasks/*",
                        "/api/tasks/*/evidence",
                        "/api/tasks/*/audit")
                    .hasAnyRole("OPERATOR", "APPROVER")
                    .anyRequest()
                    .denyAll())
        .build();
  }
}
