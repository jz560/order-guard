package com.example.orderagent.simulator.recovery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** 独立下游协议 DTO；调用方和下游通过 HTTP 契约对接，不共享数据库对象。 */
public final class ProbeProtocol {
  public static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  public enum ResultStatus {
    IN_PROGRESS,
    SUCCEEDED,
    REJECTED
  }

  public record Request(
      String orderId,
      String paymentId,
      long amountMinor,
      String currency,
      long expectedVersion,
      String action) {
    public Request {
      if (orderId == null
          || !orderId.matches("[A-Za-z0-9_-]{1,80}")
          || paymentId == null
          || !paymentId.matches("[A-Za-z0-9_-]{1,90}")
          || amountMinor <= 0
          || expectedVersion <= 0
          || !"CNY".equals(currency)
          || !"VERIFY_PAYMENT_AND_ADVANCE_ORDER".equals(action))
        throw new IllegalArgumentException("INVALID_REQUEST");
    }

    public String hash() {
      try {
        return HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(
                        JSON.writeValueAsString(
                                List.of(
                                    action,
                                    orderId,
                                    paymentId,
                                    amountMinor,
                                    currency,
                                    expectedVersion))
                            .getBytes(StandardCharsets.UTF_8)));
      } catch (java.security.NoSuchAlgorithmException failure) {
        throw new IllegalStateException(failure);
      }
    }
  }

  public record StoredOperation(
      String operationId,
      String requestHash,
      String requestJson,
      ResultStatus status,
      String code,
      long beforeVersion,
      long afterVersion) {
    public Operation response() {
      return new Operation(operationId, requestHash, status, code, beforeVersion, afterVersion);
    }
  }

  public record Operation(
      String operationId,
      String requestHash,
      ResultStatus status,
      String code,
      long beforeVersion,
      long afterVersion) {}

  public record Order(
      String orderId,
      String status,
      String paymentId,
      long amountMinor,
      String currency,
      String paymentStatus,
      long paymentAmountMinor,
      String paymentCurrency,
      long version,
      long effectCount) {}

  public static final class Conflict extends RuntimeException {
    public Conflict() {
      super("OPERATION_ID_CONFLICT");
    }
  }
}
