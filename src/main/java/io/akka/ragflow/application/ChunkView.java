package io.akka.ragflow.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import io.akka.ragflow.domain.Chunk;
import io.akka.ragflow.domain.TaskEvent;
import java.util.List;

/**
 * Every chunk a task has indexed, queryable by document (SPEC-001 §3 rule 22).
 *
 * <p>One row per task, written only on {@code Indexed} or {@code Reused} — the same events that
 * move a {@code TaskEntity} to {@code DONE} (rules 20-21). A row for a task therefore cannot exist
 * before that task is {@code DONE}: there is no intermediate write to race against a reader.
 */
@Component(id = "chunk-index")
public class ChunkView extends View {

  public record TaskEntry(String taskId, String docId, List<Chunk> chunks) {}

  public record TaskEntries(List<TaskEntry> tasks) {}

  @Query("SELECT * AS tasks FROM chunk_index WHERE docId = :docId")
  public QueryEffect<TaskEntries> forDocument(String docId) {
    return queryResult();
  }

  @Consume.FromEventSourcedEntity(TaskEntity.class)
  public static class Updater extends TableUpdater<TaskEntry> {

    public Effect<TaskEntry> onEvent(TaskEvent event) {
      String taskId = updateContext().eventSubject().orElseThrow();
      return switch (event) {
        case TaskEvent.Indexed e -> effects().updateRow(new TaskEntry(taskId, e.docId(), e.chunks()));
        case TaskEvent.Reused e -> effects().updateRow(new TaskEntry(taskId, e.docId(), e.chunks()));
        default -> effects().ignore();
      };
    }
  }
}
