package com.example.orderagent.backend.security;

import com.example.orderagent.backend.api.ApiException;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record WorkbenchAccount(String username, String password, String role, String orderPrefix)
    implements UserDetails {
  @Override
  public String getUsername() {
    return username;
  }

  @Override
  public String getPassword() {
    return password;
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_" + role));
  }

  public void requireOrder(String orderId) {
    if (!orderId.startsWith(orderPrefix)) throw new ApiException(403, "FORBIDDEN", "无权访问此订单");
  }

  public void requireCreator() {
    if (!role.equals("OPERATOR")) throw new ApiException(403, "FORBIDDEN", "当前账号不能创建任务");
  }

  @Override
  public String toString() {
    return "WorkbenchAccount[username=" + username + ",role=" + role + "]";
  }
}
