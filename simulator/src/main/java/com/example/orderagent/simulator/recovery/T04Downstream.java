package com.example.orderagent.simulator.recovery;

import com.example.orderagent.simulator.recovery.ProbeProtocol.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 独立 demo/test HTTP 进程；非 Spring Bean，不改变普通服务的权限或路由。 */
public final class T04Downstream {
  private final ProbeDownstreamStore store;
  private final String readToken, writeToken;
  private final boolean dropResponse;
  private final AtomicInteger writes = new AtomicInteger(),
      queries = new AtomicInteger(),
      orderQueries = new AtomicInteger();

  public T04Downstream(
      ProbeDownstreamStore store, String readToken, String writeToken, boolean dropResponse) {
    if (readToken.length() < 16 || writeToken.length() < 16 || readToken.equals(writeToken))
      throw new IllegalArgumentException("INVALID_PROBE_TOKENS");
    this.store = store;
    this.readToken = readToken;
    this.writeToken = writeToken;
    this.dropResponse = dropResponse;
  }

  private void handle(HttpExchange exchange) throws IOException {
    String id = "none";
    try {
      String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
      if ("/health".equals(path) && "GET".equals(method)) {
        respond(exchange, 200, Map.of("status", "UP"));
        return;
      }
      String token = "PUT".equals(method) ? writeToken : readToken;
      var supplied = Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"), "");
      if (!MessageDigest.isEqual(
          supplied.getBytes(StandardCharsets.UTF_8),
          ("Bearer " + token).getBytes(StandardCharsets.UTF_8))) {
        error(exchange, 403, "FORBIDDEN", UUID.randomUUID().toString());
        return;
      }
      if ("/t04/stats".equals(path) && "GET".equals(method)) {
        respond(
            exchange,
            200,
            Map.of(
                "writeRequests",
                writes.get(),
                "operationQueries",
                queries.get(),
                "orderQueries",
                orderQueries.get()));
        return;
      }
      if (path.startsWith("/t04/orders/") && "GET".equals(method)) {
        id = path.substring("/t04/orders/".length());
        if (!id.matches("[A-Za-z0-9_-]{1,80}"))
          throw new IllegalArgumentException("INVALID_ORDER_ID");
        orderQueries.incrementAndGet();
        var order = store.order(id);
        if (order == null) error(exchange, 404, "NOT_FOUND", id);
        else respond(exchange, 200, order);
        return;
      }
      if (!path.startsWith("/t04/operations/")) {
        error(exchange, 404, "NOT_FOUND", UUID.randomUUID().toString());
        return;
      }
      id = UUID.fromString(path.substring("/t04/operations/".length())).toString();
      Operation result;
      if ("PUT".equals(method)) {
        byte[] bytes = exchange.getRequestBody().readNBytes(16385);
        if (bytes.length > 16384) {
          error(exchange, 413, "REQUEST_TOO_LARGE", id);
          return;
        }
        var request = ProbeProtocol.JSON.readValue(bytes, Request.class);
        if (request == null) throw new IllegalArgumentException("INVALID_REQUEST");
        writes.incrementAndGet();
        result = store.execute(id, request);
        if (dropResponse) {
          // 事务已经提交；发送不完整响应后断开，客户端无法取得完整结果。
          exchange.sendResponseHeaders(200, 128);
          exchange.getResponseBody().write('{');
          exchange.close();
          return;
        }
      } else if ("GET".equals(method)) {
        queries.incrementAndGet();
        result = store.operation(id);
      } else {
        error(exchange, 405, "METHOD_NOT_ALLOWED", id);
        return;
      }
      if (result == null) error(exchange, 404, "OPERATION_NOT_FOUND", id);
      else respond(exchange, result.status() == ResultStatus.REJECTED ? 422 : 200, result);
    } catch (Conflict failure) {
      error(exchange, 409, "OPERATION_ID_CONFLICT", id);
    } catch (IllegalArgumentException | tools.jackson.core.JacksonException failure) {
      error(exchange, 400, "INVALID_REQUEST", id);
    } catch (IOException failure) {
      exchange.close();
    } catch (RuntimeException failure) {
      error(exchange, 500, "STORAGE_FAILURE", id);
    } finally {
      exchange.close();
    }
  }

  private void error(HttpExchange exchange, int status, String code, String id) throws IOException {
    String message =
        switch (code) {
          case "FORBIDDEN" -> "凭证无效或没有此操作的权限";
          case "NOT_FOUND" -> "未找到请求的资源";
          case "OPERATION_NOT_FOUND" -> "本次查询未找到操作记录，请保留结果未知状态";
          case "OPERATION_ID_CONFLICT" -> "此操作 ID 已绑定不同参数";
          case "INVALID_REQUEST" -> "请求格式或参数不符合实验协议";
          case "REQUEST_TOO_LARGE" -> "请求内容超过允许长度";
          case "METHOD_NOT_ALLOWED" -> "此接口不支持请求的方法";
          default -> "存储处理失败，请查询原操作确认结果";
        };
    respond(exchange, status, Map.of("code", code, "message", message, "correlationId", id));
  }

  private void respond(HttpExchange exchange, int status, Object response) throws IOException {
    byte[] bytes = ProbeProtocol.JSON.writeValueAsBytes(response);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  private static String required(String key) {
    String value = System.getenv(key);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("MISSING_" + key);
    return value;
  }

  public static void main(String[] args) {
    try {
      if (!Set.of("demo", "test").contains(required("T04_MODE")))
        throw new IllegalArgumentException("T04_DEMO_TEST_ONLY");
      String fault = System.getenv().getOrDefault("T04_FAULT", "NONE");
      if (!Set.of("NONE", "DROP_RESPONSE").contains(fault))
        throw new IllegalArgumentException("INVALID_FAULT");
      var store =
          new ProbeDownstreamStore(
              required("T04_DB_URL"), required("T04_DB_USER"), required("T04_DB_PASSWORD"));
      if (System.getenv("T04_SEED_ORDER_ID") != null) store.seed(required("T04_SEED_ORDER_ID"));
      var app =
          new T04Downstream(
              store,
              required("T04_READ_TOKEN"),
              required("T04_WRITE_TOKEN"),
              "DROP_RESPONSE".equals(fault));
      var server =
          HttpServer.create(
              new InetSocketAddress(
                  "0.0.0.0", Integer.parseInt(System.getenv().getOrDefault("T04_PORT", "8094"))),
              0);
      server.createContext("/", app::handle);
      server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
      server.start();
      Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
      System.out.println("T04_READY port=" + server.getAddress().getPort());
    } catch (Exception failure) {
      System.err.println("T04_ERROR START_OR_CONFIGURATION_FAILURE");
      System.exit(2);
    }
  }
}
