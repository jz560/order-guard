package com.example.orderagent.simulator.task;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;

/** 复用 T06 的独立服务测试夹具，调度器运行在另外的真实 JVM 中。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class T07WorkerIT {
  final T06TaskIT fixture = new T06TaskIT();
  final List<GenericContainer<?>> workers = new ArrayList<>();

  @BeforeAll
  void start() throws Exception {
    fixture.start();
  }

  @AfterEach
  void stopWorkers() {
    workers.forEach(GenericContainer::close);
    workers.clear();
  }

  @AfterAll
  void stop() {
    fixture.stop();
  }

  GenericContainer<?> worker(long stepDelay) {
    var worker =
        fixture
            .process("backend")
            .withEnv("WORKER_ENABLED", "true")
            .withEnv("WORKER_POLL_MS", "100")
            .withEnv("WORKER_STEP_DELAY_MS", Long.toString(stepDelay))
            .withEnv("MODEL_MODE", "stub");
    workers.add(worker);
    worker.start();
    return worker;
  }

  void awaitState(String id, String state) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      if (state.equals(
          fixture.scalar("agent_app", "SELECT state FROM task WHERE id=?", UUID.fromString(id))))
        return;
      Thread.sleep(100);
    }
    fail("任务没有到达预期状态：" + state);
  }

  String create() throws Exception {
    return fixture
        .data(fixture.create(fixture.order(), fixture.id()), 201)
        .get("task")
        .get("id")
        .asText();
  }

  @Test
  void twoIndependentWorkersProgressAfterRequestAndReleaseWaitingTasks() throws Exception {
    worker(100);
    worker(100);
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 6; i++) ids.add(create());
    // 不再维持请求或读取页面；只在测试侧检查数据库最终状态。
    for (String id : ids) {
      awaitState(id, "WAITING_APPROVAL");
      assertThat(
              fixture.scalar(
                  "agent_app",
                  "SELECT count(*) FROM task_step WHERE task_id=?",
                  UUID.fromString(id)))
          .isEqualTo("2");
      assertThat(
              fixture.scalar(
                  "agent_app",
                  "SELECT count(*) FROM task WHERE id=? AND lease_owner IS NULL AND lease_until IS NULL AND next_run_at IS NULL",
                  UUID.fromString(id)))
          .isEqualTo("1");
      var steps =
          fixture.data(
              fixture.http("GET", "/api/tasks/" + id + "/steps", "operator", null, null, false),
              200);
      assertThat(steps.size()).isEqualTo(2);
      assertThat(steps.get(1).get("output").asText()).contains("没有真实诊断结论或可审批方案");
      long cursor = steps.get(0).get("id").asLong();
      assertThat(
              fixture
                  .data(
                      fixture.http(
                          "GET",
                          "/api/tasks/" + id + "/steps?after=" + cursor + "&limit=1",
                          "approver",
                          null,
                          null,
                          false),
                      200)
                  .size())
          .isEqualTo(1);
      fixture.data(
          fixture.http("GET", "/api/tasks/" + id + "/steps", null, null, null, false), 401);
    }
    // 已有等待审批任务不占用处理槽，后来的任务仍能正常推进。
    String later = create();
    awaitState(later, "WAITING_APPROVAL");
    assertThat(fixture.scalar("agent_simulator", "SELECT count(*) FROM operations")).isEqualTo("0");
    UUID hidden = UUID.randomUUID();
    fixture.update(
        "INSERT INTO task(id,order_id,state,created_by,next_run_at) VALUES(?,'OTHER-T07-SECRET','QUEUED','operator',NULL)",
        hidden);
    fixture.data(
        fixture.http("GET", "/api/tasks/" + hidden + "/steps", "operator", null, null, false), 404);
    fixture.data(
        fixture.http(
            "GET", "/api/tasks/" + later + "/steps?limit=101", "operator", null, null, false),
        400);
  }

  @Test
  void newProcessResumesPersistedNextStepWithoutRepeatingFirstStep() throws Exception {
    var first = worker(10000);
    String id = create();
    awaitState(id, "DIAGNOSING");
    assertThat(
            fixture.scalar(
                "agent_app", "SELECT count(*) FROM task_step WHERE task_id=?", UUID.fromString(id)))
        .isEqualTo("1");
    first.close();
    workers.remove(first);
    assertThat(
            fixture.scalar(
                "agent_app",
                "SELECT next_run_at IS NOT NULL FROM task WHERE id=?",
                UUID.fromString(id)))
        .isEqualTo("t");
    worker(0);
    awaitState(id, "WAITING_APPROVAL");
    assertThat(
            fixture.scalar(
                "agent_app",
                "SELECT count(*) FROM task_step WHERE task_id=? AND type='TEST_START'",
                UUID.fromString(id)))
        .isEqualTo("1");
    assertThat(
            fixture.scalar(
                "agent_app",
                "SELECT count(*) FROM task_step WHERE task_id=? AND type='TEST_WAIT'",
                UUID.fromString(id)))
        .isEqualTo("1");
    assertThat(
            fixture.scalar(
                "agent_app",
                "SELECT count(DISTINCT actor) FROM audit_event WHERE task_id=? AND event_type='WORKER_CLAIMED'",
                UUID.fromString(id)))
        .isEqualTo("2");
  }
}
