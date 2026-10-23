package com.example.orderagent.backend.recovery;

import com.example.orderagent.backend.recovery.ProbeProtocol.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** 只有显式启动的 demo/test CLI 使用；恢复路径只查询，不重发 PUT。 */
public final class T04Caller {
  private final ProbeIntentStore store;
  private final URI base;
  private final String readToken, writeToken;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  public T04Caller(ProbeIntentStore store, URI base, String readToken, String writeToken) {
    if (!"http".equals(base.getScheme())
        || base.getHost() == null
        || base.getUserInfo() != null
        || base.getQuery() != null) throw new IllegalArgumentException("INVALID_DOWNSTREAM_URL");
    this.store = store;
    this.base = base;
    this.readToken = readToken;
    this.writeToken = writeToken;
  }

  public Intent send(String id, boolean holdBeforeSave) throws InterruptedException {
    var intent = store.find(id);
    if (intent == null) throw new IllegalArgumentException("INTENT_NOT_FOUND");
    if (!store.claim(id)) return recover(id);
    try {
      var response =
          exchange(
              "/t04/operations/" + id,
              "PUT",
              ProbeProtocol.JSON.writeValueAsString(intent.request()),
              writeToken);
      if (holdBeforeSave) {
        System.out.println("T04_CHECKPOINT AFTER_RESPONSE_BEFORE_SAVE operationId=" + id);
        System.out.flush();
        new CountDownLatch(1).await();
      }
      return accept(id, intent.request(), response);
    } catch (IOException failure) {
      return store.finish(id, State.UNKNOWN, "TRANSPORT_RESULT_UNKNOWN");
    }
  }

  public Intent recover(String id) throws InterruptedException {
    var intent = store.find(id);
    if (intent == null) throw new IllegalArgumentException("INTENT_NOT_FOUND");
    if (intent.status() == State.SUCCEEDED || intent.status() == State.REJECTED) return intent;
    try {
      return accept(
          id, intent.request(), exchange("/t04/operations/" + id, "GET", null, readToken));
    } catch (IOException failure) {
      return store.finish(id, State.UNKNOWN, "QUERY_RESULT_UNKNOWN");
    }
  }

  private Intent accept(String id, Request request, HttpResponse<String> response)
      throws IOException, InterruptedException {
    if (response.statusCode() == 404)
      return store.finish(id, State.UNKNOWN, "OPERATION_NOT_FOUND_UNKNOWN");
    if (response.statusCode() != 200 && response.statusCode() != 422)
      return store.finish(id, State.UNKNOWN, "DOWNSTREAM_HTTP_" + response.statusCode());
    Operation result;
    try {
      result = ProbeProtocol.JSON.readValue(response.body(), Operation.class);
    } catch (tools.jackson.core.JacksonException failure) {
      return store.finish(id, State.UNKNOWN, "INVALID_OPERATION_RESPONSE");
    }
    if (result == null
        || !id.equals(result.operationId())
        || !request.hash().equals(result.requestHash()))
      return store.finish(id, State.UNKNOWN, "OPERATION_BINDING_MISMATCH");
    if ("REJECTED".equals(result.status()) && response.statusCode() == 422)
      return store.finish(id, State.REJECTED, result.code());
    if (!"SUCCEEDED".equals(result.status()) || response.statusCode() != 200)
      return store.finish(id, State.UNKNOWN, "OPERATION_NOT_FINAL");
    var verified = exchange("/t04/orders/" + request.orderId(), "GET", null, readToken);
    if (verified.statusCode() != 200)
      return store.finish(id, State.UNKNOWN, "ORDER_VERIFICATION_UNAVAILABLE");
    Order order;
    try {
      order = ProbeProtocol.JSON.readValue(verified.body(), Order.class);
    } catch (tools.jackson.core.JacksonException failure) {
      return store.finish(id, State.UNKNOWN, "INVALID_ORDER_RESPONSE");
    }
    if (order == null
        || !request.orderId().equals(order.orderId())
        || !"PAID".equals(order.status())
        || !request.paymentId().equals(order.paymentId())
        || request.amountMinor() != order.amountMinor()
        || !request.currency().equals(order.currency())
        || !"SUCCESS".equals(order.paymentStatus())
        || request.amountMinor() != order.paymentAmountMinor()
        || !request.currency().equals(order.paymentCurrency())
        || result.beforeVersion() != request.expectedVersion()
        || result.afterVersion() != request.expectedVersion() + 1
        || order.version() != result.afterVersion())
      return store.finish(id, State.UNKNOWN, "BUSINESS_VERIFICATION_FAILED");
    return store.finish(id, State.SUCCEEDED, "VERIFIED");
  }

  private HttpResponse<String> exchange(String path, String method, String body, String token)
      throws IOException, InterruptedException {
    var builder =
        HttpRequest.newBuilder(base.resolve(path))
            .timeout(Duration.ofSeconds(3))
            .header("Authorization", "Bearer " + token);
    if (body == null) builder.GET();
    else
      builder
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body));
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String required(String name) {
    var value = System.getenv(name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("MISSING_" + name);
    return value;
  }

  public static void main(String[] args) {
    try {
      if (!java.util.Set.of("demo", "test").contains(required("T04_MODE")))
        throw new IllegalArgumentException("T04_DEMO_TEST_ONLY");
      if (args.length != 1 || !java.util.Set.of("prepare", "send", "recover").contains(args[0]))
        throw new IllegalArgumentException("INVALID_COMMAND");
      var store =
          new ProbeIntentStore(
              required("T04_DB_URL"), required("T04_DB_USER"), required("T04_DB_PASSWORD"));
      String id = required("T04_OPERATION_ID");
      Intent result;
      if ("recover".equals(args[0])) {
        result =
            new T04Caller(
                    store,
                    URI.create(required("T04_DOWNSTREAM_URL")),
                    required("T04_READ_TOKEN"),
                    "")
                .recover(id);
      } else {
        String order = required("T04_ORDER_ID");
        result =
            store.prepare(
                id,
                new Request(
                    order,
                    "T04-PAY-" + order,
                    10000,
                    "CNY",
                    1,
                    "VERIFY_PAYMENT_AND_ADVANCE_ORDER"));
        if ("send".equals(args[0]))
          result =
              new T04Caller(
                      store,
                      URI.create(required("T04_DOWNSTREAM_URL")),
                      required("T04_READ_TOKEN"),
                      required("T04_WRITE_TOKEN"))
                  .send(id, "AFTER_RESPONSE_BEFORE_SAVE".equals(System.getenv("T04_PAUSE")));
      }
      System.out.println(
          "T04_RESULT "
              + ProbeProtocol.JSON.writeValueAsString(
                  Map.of(
                      "operationId", id, "status", result.status(), "code", result.lastError())));
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      System.err.println("T04_ERROR INTERRUPTED");
      System.exit(2);
    } catch (Exception failure) {
      System.err.println(
          "T04_ERROR "
              + (failure instanceof IllegalArgumentException
                  ? failure.getMessage()
                  : "START_OR_STORAGE_FAILURE"));
      System.exit(2);
    }
  }
}
