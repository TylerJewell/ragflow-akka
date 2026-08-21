package io.akka.ragflow.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SPEC-001 §2 Document. */
public record DocumentState(
    String id,
    String kbId,
    String name,
    FileType fileType,
    List<String> sections,
    ParserConfig parserConfig,
    DocumentStatus status,
    double progress,
    String progressMsg,
    int chunkNum,
    int tokenNum,
    List<TaskSummary> tasks,
    int parseGeneration) {

  public static DocumentState empty(String id) {
    return new DocumentState(
        id, null, null, null, List.of(), null, DocumentStatus.UNSTART, 0.0, "", 0, 0, List.of(), 0);
  }

  public boolean exists() {
    return kbId != null;
  }

  /** Rule 14: a previously *completed* task with the same page range and digest. */
  public Optional<TaskSummary> findReusable(int fromPage, String digest) {
    return tasks.stream()
        .filter(t -> t.status() == TaskStatus.DONE)
        .filter(t -> t.fromPage() == fromPage && digest.equals(t.digest()))
        .findFirst();
  }

  public DocumentState withTask(TaskSummary summary) {
    List<TaskSummary> next = new ArrayList<>();
    boolean replaced = false;
    for (TaskSummary t : tasks) {
      if (t.taskId().equals(summary.taskId())) {
        next.add(summary);
        replaced = true;
      } else {
        next.add(t);
      }
    }
    if (!replaced) {
      next.add(summary);
    }
    return new DocumentState(
        id, kbId, name, fileType, sections, parserConfig, status, progress, progressMsg,
        chunkNum, tokenNum, next, parseGeneration);
  }

  public DocumentState withRollup(DocumentRollup.Result rollup, String msg) {
    int chunks = tasks.stream().mapToInt(TaskSummary::chunkCount).sum();
    int tokens = tasks.stream().mapToInt(TaskSummary::tokenCount).sum();
    return new DocumentState(
        id, kbId, name, fileType, sections, parserConfig, rollup.status(), rollup.progress(),
        msg, chunks, tokens, tasks, parseGeneration);
  }

  public DocumentState withParseGeneration(int generation) {
    return new DocumentState(
        id, kbId, name, fileType, sections, parserConfig, status, progress, progressMsg,
        chunkNum, tokenNum, tasks, generation);
  }

  /** Row 15: a re-parse wipes the previous generation's tasks before the new ones are queued. */
  public DocumentState withTasksCleared() {
    return new DocumentState(
        id, kbId, name, fileType, sections, parserConfig, status, progress, progressMsg,
        chunkNum, tokenNum, List.of(), parseGeneration);
  }
}
