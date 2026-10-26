package com.example.orderagent.simulator.business;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
  private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

  public record Error(String code, String message, String correlationId) {}

  public static String correlation(HttpServletRequest request) {
    Object existing = request.getAttribute("correlationId");
    if (existing != null) return existing.toString();
    String id = UUID.randomUUID().toString();
    request.setAttribute("correlationId", id);
    return id;
  }

  public static void forbidden(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setStatus(403);
    response.setContentType("application/json;charset=UTF-8");
    response
        .getWriter()
        .write(
            "{\"code\":\"FORBIDDEN\",\"message\":\"无访问权限\",\"correlationId\":\""
                + correlation(request)
                + "\"}");
  }

  @ExceptionHandler(BusinessException.class)
  ResponseEntity<Error> business(BusinessException ex, HttpServletRequest request) {
    return ResponseEntity.status(ex.status())
        .body(new Error(ex.code(), ex.getMessage(), correlation(request)));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class
  })
  ResponseEntity<Error> invalid(Exception ex, HttpServletRequest request) {
    return ResponseEntity.badRequest()
        .body(new Error("INVALID_REQUEST", "参数缺失或格式不正确", correlation(request)));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Error> unexpected(Exception ex, HttpServletRequest request) {
    String id = correlation(request);
    // 不输出 SQL 参数、请求体或认证头。
    LOG.error("simulator error correlationId={} type={}", id, ex.getClass().getSimpleName());
    return ResponseEntity.internalServerError()
        .body(new Error("INTERNAL_ERROR", "服务暂不可用；请查询原操作 ID 确认结果", id));
  }
}
