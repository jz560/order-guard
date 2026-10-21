package com.example.orderagent.backend.diagnosis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;

/** 每个事件立即追加；记录失败时停止调用，不静默丢弃审计。 */
public final class ProbeTrace implements Consumer<Map<String, Object>> {
  private final Path file;
  private final String runId = UUID.randomUUID().toString();
  private final JsonMapper json = JsonMapper.builder().build();

  public ProbeTrace(Path directory) throws IOException {
    Files.createDirectories(directory);
    file = directory.resolve(runId + ".jsonl");
    Files.createFile(file);
  }

  public Path file() {
    return file;
  }

  @Override
  public void accept(Map<String, Object> event) {
    try {
      Files.writeString(
          file,
          json.writeValueAsString(
                  Map.of("runId", runId, "at", Instant.now().toString(), "event", event))
              + System.lineSeparator(),
          StandardOpenOption.APPEND);
    } catch (IOException failure) {
      throw new UncheckedIOException("诊断记录保存失败，停止实验", failure);
    }
  }
}
