package io.akka.ragflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.ragflow.domain.Chunk;
import io.akka.ragflow.domain.TaskCommand;
import io.akka.ragflow.domain.TaskEvent;
import io.akka.ragflow.domain.TaskState;
import io.akka.ragflow.domain.TaskStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rules 13-22, question-log rows 9, 10, 12. */
class TaskEntityTest {

  private static EventSourcedTestKit<TaskState, TaskEvent, TaskEntity> kit() {
    return EventSourcedTestKit.of("doc-1/t0", TaskEntity::new);
  }

  private static Chunk chunk(String id) {
    return new Chunk(id, "doc-1", "doc-1/t0", "kb-1", "content", 3, List.of(), List.of(0.1));
  }

  // rule 16
  @Test
  void theThirdDispatchAbandonsRatherThanProcesses() {
    var kit = kit();
    kit.method(TaskEntity::queue).invoke(new TaskCommand.Queue("doc-1", 0, 12, "digest-1"));

    var first = kit.method(TaskEntity::beginAttempt).invoke();
    assertThat(first.getReply().abandoned()).isFalse();
    assertThat(first.getReply().retryCount()).isEqualTo(1);

    var second = kit.method(TaskEntity::beginAttempt).invoke();
    assertThat(second.getReply().abandoned()).isFalse();
    assertThat(second.getReply().retryCount()).isEqualTo(2);

    var third = kit.method(TaskEntity::beginAttempt).invoke();
    assertThat(third.getReply().abandoned()).isTrue();
    assertThat(kit.getState().status()).isEqualTo(TaskStatus.FAIL);
    assertThat(kit.getState().progress()).isEqualTo(-1.0);
  }

  // rule 15
  @Test
  void progressCanOnlyMoveForwardOrToFailure() {
    var kit = kit();
    kit.method(TaskEntity::queue).invoke(new TaskCommand.Queue("doc-1", 0, 12, "digest-1"));
    kit.method(TaskEntity::reportProgress).invoke(new TaskCommand.ReportProgress(0.5, "half"));
    assertThat(kit.getState().progress()).isEqualTo(0.5);

    kit.method(TaskEntity::reportProgress).invoke(new TaskCommand.ReportProgress(0.3, "stale"));
    assertThat(kit.getState().progress()).isEqualTo(0.5); // rejected: not an increase

    kit.method(TaskEntity::reportProgress).invoke(new TaskCommand.ReportProgress(-1.0, "failed"));
    assertThat(kit.getState().progress()).isEqualTo(-1.0);

    kit.method(TaskEntity::reportProgress).invoke(new TaskCommand.ReportProgress(0.2, "stale-after-fail"));
    assertThat(kit.getState().progress()).isEqualTo(-1.0); // rejected: current is already -1

    kit.method(TaskEntity::reportProgress).invoke(new TaskCommand.ReportProgress(1.0, "recovered"));
    assertThat(kit.getState().progress()).isEqualTo(1.0); // recovery to >=1 is always accepted
  }

  // rules 20-22
  @Test
  void indexingMovesTheTaskToDoneAndCarriesTheChunks() {
    var kit = kit();
    kit.method(TaskEntity::queue).invoke(new TaskCommand.Queue("doc-1", 0, 12, "digest-1"));
    kit.method(TaskEntity::index).invoke(new TaskCommand.Index(List.of(chunk("c1"))));

    assertThat(kit.getState().status()).isEqualTo(TaskStatus.DONE);
    assertThat(kit.getState().progress()).isEqualTo(1.0);
    assertThat(kit.getState().chunks()).extracting(Chunk::id).containsExactly("c1");
  }

  @Test
  void readChunksIsRefusedUntilTheTaskIsDone() {
    var kit = kit();
    kit.method(TaskEntity::queue).invoke(new TaskCommand.Queue("doc-1", 0, 12, "digest-1"));
    assertThat(kit.method(TaskEntity::readChunks).invoke().isError()).isTrue();

    kit.method(TaskEntity::index).invoke(new TaskCommand.Index(List.of(chunk("c1"))));
    assertThat(kit.method(TaskEntity::readChunks).invoke().getReply()).extracting(Chunk::id).containsExactly("c1");
  }

  // rule 14
  @Test
  void reuseMovesStraightToDoneWithoutAnAttempt() {
    var kit = kit();
    kit.method(TaskEntity::queue).invoke(new TaskCommand.Queue("doc-1", 0, 12, "digest-1"));
    kit.method(TaskEntity::reuse).invoke(new TaskCommand.Reuse(List.of(chunk("c1"))));

    assertThat(kit.getState().status()).isEqualTo(TaskStatus.DONE);
    assertThat(kit.getState().progress()).isEqualTo(1.0);
    assertThat(kit.getState().retryCount()).isEqualTo(0);
  }

  @Test
  void aTaskCannotBeQueuedTwice() {
    var kit = kit();
    kit.method(TaskEntity::queue).invoke(new TaskCommand.Queue("doc-1", 0, 12, "digest-1"));
    assertThat(kit.method(TaskEntity::queue).invoke(new TaskCommand.Queue("doc-1", 0, 12, "digest-1")).isError())
        .isTrue();
  }
}
