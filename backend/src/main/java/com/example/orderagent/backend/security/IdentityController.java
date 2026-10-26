package com.example.orderagent.backend.security;

import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
public class IdentityController {
  public record Identity(
      String username, String role, String orderPrefix, List<String> allowedActions) {}

  public record Csrf(String headerName, String token) {}

  @GetMapping("/api/me")
  public Identity identity(@AuthenticationPrincipal WorkbenchAccount account) {
    return new Identity(
        account.username(),
        account.role(),
        account.orderPrefix(),
        account.role().equals("OPERATOR")
            ? List.of("CREATE_TASK", "VIEW_TASKS")
            : List.of("VIEW_TASKS"));
  }

  @GetMapping("/api/auth/csrf")
  public Csrf csrf(CsrfToken token) {
    return new Csrf(token.getHeaderName(), token.getToken());
  }
}
