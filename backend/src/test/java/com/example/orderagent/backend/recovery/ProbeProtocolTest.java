package com.example.orderagent.backend.recovery;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProbeProtocolTest {
  @Test
  void hashMatchesFrozenCrossServiceContract() {
    var request =
        new ProbeProtocol.Request(
            "ORDER-1", "PAY-1", 10000, "CNY", 1, "VERIFY_PAYMENT_AND_ADVANCE_ORDER");
    assertThat(request.hash())
        .isEqualTo("0d95fe18e0c4b8bb74e8e9f0c873be2082e5e8b4a4317147c3a253aa43dbac11");
    assertThat(
            new ProbeProtocol.Request(
                    "ORDER-1", "PAY-1", 9999, "CNY", 1, "VERIFY_PAYMENT_AND_ADVANCE_ORDER")
                .hash())
        .isNotEqualTo(request.hash());
    assertThat(
            new ProbeProtocol.Request(
                    "ORDER-1", "PAY-1", 10000, "CNY", 2, "VERIFY_PAYMENT_AND_ADVANCE_ORDER")
                .hash())
        .isNotEqualTo(request.hash());
  }

  @Test
  void rejectsInvalidBusinessParametersAndNonCanonicalJson() {
    assertThatThrownBy(
            () ->
                new ProbeProtocol.Request(
                    "ORDER-1", "PAY-1", 0, "CNY", 1, "VERIFY_PAYMENT_AND_ADVANCE_ORDER"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ProbeProtocol.Request(
                    "ORDER-1", "PAY-1", 10000, "USD", 1, "VERIFY_PAYMENT_AND_ADVANCE_ORDER"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ProbeProtocol.Request("ORDER-1", "PAY-1", 10000, "CNY", 1, "REFUND"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ProbeProtocol.JSON.readValue(
                    "{\"orderId\":\"A\",\"orderId\":\"B\"}", ProbeProtocol.Request.class))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(() -> ProbeProtocol.JSON.readValue("{} {}", ProbeProtocol.Request.class))
        .isInstanceOf(RuntimeException.class);
  }
}
