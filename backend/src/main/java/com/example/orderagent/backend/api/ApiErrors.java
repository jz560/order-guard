package com.example.orderagent.backend.api;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.*;

@RestControllerAdvice
public class ApiErrors {
  private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

  public record Error(String code, String message, String correlationId) {}

  public static String correlation(HttpServletRequest request) {
    var value = request.getAttribute("correlationId");
    if (value == null) {
      value = UUID.randomUUID().toString();
      request.setAttribute("correlationId", value);
    }
    return value.toString();
  }

  public static void security(HttpServletRequest request, HttpServletResponse response, int status)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    response
        .getWriter()
        .write(
            "{\"code\":\""
                + (status == 401 ? "UNAUTHENTICATED" : "FORBIDDEN")
                + "\",\"message\":\""
                + (status == 401 ? "请提供有效账号凭证" : "无权限或请求缺少有效 CSRF 令牌")
                + "\",\"correlationId\":\""
                + correlation(request)
                + "\"}");
  }

  @ExceptionHandler(ApiException.class)
  ResponseEntity<Error> business(ApiException ex, HttpServletRequest request) {
    return ResponseEntity.status(ex.status())
        .body(new Error(ex.code(), ex.getMessage(), correlation(request)));
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MethodArgumentTypeMismatchException.class,
    MissingRequestHeaderException.class,
    HandlerMethodValidationException.class
  })
  ResponseEntity<Error> invalid(Exception ex, HttpServletRequest request) {
    return ResponseEntity.badRequest()
        .body(new Error("INVALID_REQUEST", "参数缺失或格式不正确", correlation(request)));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Error> unexpected(Exception ex, HttpServletRequest request) {
    String id = correlation(request);
    LOG.error("backend request error correlationId={} type={}", id, ex.getClass().getSimpleName());
    return ResponseEntity.internalServerError()
        .body(new Error("INTERNAL_ERROR", "服务暂不可用，请使用原幂等键重试", id));
  }
}
