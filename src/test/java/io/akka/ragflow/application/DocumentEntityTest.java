package io.akka.ragflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.ragflow.domain.DocumentCommand;
import io.akka.ragflow.domain.DocumentEvent;
import io.akka.ragflow.domain.DocumentState;
import io.akka.ragflow.domain.DocumentStatus;
import io.akka.ragflow.domain.FileType;
import io.akka.ragflow.domain.ParserConfig;
import io.akka.ragflow.domain.TaskStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rules 12-14, 17-19, question-log rows 8, 9, 11. */
class DocumentEntityTest {

  private static EventSourcedTestKit<DocumentState, DocumentEvent, DocumentEntity> kit() {
    return EventSourcedTestKit.of("doc-1", DocumentEntity::new);
  }

  // rule 12
  @Test
  void aPdfDocumentIsSplitIntoPageWindowTasks() {
    var kit = kit();
    List<String> pages = List.of("p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9", "p10", "p11", "p12", "p13");
    kit.method(DocumentEntity::create)
        .invoke(new DocumentCommand.Create("kb-1", "doc.pdf", FileType.PDF, pages, ParserConfig.defaults()));

    var tasks = kit.method(DocumentEntity::parse).invoke().getReply();
    assertThat(tasks).hasSize(2);
    assertThat(tasks.get(0).fromPage()).isEqualTo(0);
    assertThat(tasks.get(0).toPage()).isEqualTo(12);
    assertThat(tasks.get(1).fromPage()).isEqualTo(12);
    assertThat(tasks.get(1).toPage()).isEqualTo(13);
  }

  @Test
  void aTextDocumentIsASingleTask() {
    var kit = kit();
    kit.method(DocumentEntity::create)
        .invoke(new DocumentCommand.Create("kb-1", "doc.txt", FileType.TEXT, List.of("hello"), ParserConfig.defaults()));
    var tasks = kit.method(DocumentEntity::parse).invoke().getReply();
    assertThat(tasks).hasSize(1);
  }

  // rule 14
  @Test
  void reparseReusesAnUnchangedRange() {
    var kit = kit();
    kit.method(DocumentEntity::create)
        .invoke(new DocumentCommand.Create("kb-1", "doc.txt", FileType.TEXT, List.of("hello"), ParserConfig.defaults()));

    var firstPass = kit.method(DocumentEntity::parse).invoke().getReply();
    assertThat(firstPass.get(0).reuseFromTaskId()).isNull(); // nothing to reuse yet

    String taskId = firstPass.get(0).taskId();
    kit.method(DocumentEntity::reportTaskProgress)
        .invoke(
            new DocumentCommand.ReportTaskProgress(
                taskId, 0, 0, firstPass.get(0).digest(), TaskStatus.DONE, 1.0, 3, 30));

    var secondPass = kit.method(DocumentEntity::parse).invoke().getReply();
    assertThat(secondPass.get(0).reuseFromTaskId()).isEqualTo(taskId);
  }

  @Test
  void aPreviousTaskThatDidNotFinishIsNotOfferedForReuse() {
    var kit = kit();
    kit.method(DocumentEntity::create)
        .invoke(new DocumentCommand.Create("kb-1", "doc.txt", FileType.TEXT, List.of("hello"), ParserConfig.defaults()));
    var firstPass = kit.method(DocumentEntity::parse).invoke().getReply();
    String taskId = firstPass.get(0).taskId();
    // Still RUNNING, not DONE, when parse is called again — rule 14 requires progress==1.0.
    kit.method(DocumentEntity::reportTaskProgress)
        .invoke(new DocumentCommand.ReportTaskProgress(taskId, 0, 0, firstPass.get(0).digest(), TaskStatus.RUNNING, 0.5, 0, 0));

    var secondPass = kit.method(DocumentEntity::parse).invoke().getReply();
    assertThat(secondPass.get(0).reuseFromTaskId()).isNull();
  }

  // rules 17-19
  @Test
  void documentProgressIsTheMeanOfItsTasksAndFinishesDoneWhenAllAreDone() {
    var kit = kit();
    kit.method(DocumentEntity::create)
        .invoke(
            new DocumentCommand.Create(
                "kb-1", "doc.pdf", FileType.PDF,
                List.of("p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9", "p10", "p11", "p12", "p13"),
                ParserConfig.defaults()));
    var tasks = kit.method(DocumentEntity::parse).invoke().getReply();

    kit.method(DocumentEntity::reportTaskProgress)
        .invoke(new DocumentCommand.ReportTaskProgress(tasks.get(0).taskId(), 0, 12, tasks.get(0).digest(), TaskStatus.RUNNING, 0.5, 0, 0));
    assertThat(kit.getState().status()).isEqualTo(DocumentStatus.RUNNING);
    assertThat(kit.getState().progress()).isEqualTo(0.25); // (0.5 + 0) / 2

    kit.method(DocumentEntity::reportTaskProgress)
        .invoke(new DocumentCommand.ReportTaskProgress(tasks.get(0).taskId(), 0, 12, tasks.get(0).digest(), TaskStatus.DONE, 1.0, 5, 50));
    kit.method(DocumentEntity::reportTaskProgress)
        .invoke(new DocumentCommand.ReportTaskProgress(tasks.get(1).taskId(), 12, 13, tasks.get(1).digest(), TaskStatus.DONE, 1.0, 2, 20));

    assertThat(kit.getState().status()).isEqualTo(DocumentStatus.DONE);
    assertThat(kit.getState().progress()).isEqualTo(1.0);
    assertThat(kit.getState().chunkNum()).isEqualTo(7);
    assertThat(kit.getState().tokenNum()).isEqualTo(70);
  }

  @Test
  void oneFailedTaskFailsTheWholeDocumentOnceEveryTaskHasFinished() {
    var kit = kit();
    kit.method(DocumentEntity::create)
        .invoke(
            new DocumentCommand.Create(
                "kb-1", "doc.pdf", FileType.PDF,
                List.of("p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9", "p10", "p11", "p12", "p13"),
                ParserConfig.defaults()));
    var tasks = kit.method(DocumentEntity::parse).invoke().getReply();

    kit.method(DocumentEntity::reportTaskProgress)
        .invoke(new DocumentCommand.ReportTaskProgress(tasks.get(0).taskId(), 0, 12, tasks.get(0).digest(), TaskStatus.DONE, 1.0, 5, 50));
    kit.method(DocumentEntity::reportTaskProgress)
        .invoke(new DocumentCommand.ReportTaskProgress(tasks.get(1).taskId(), 12, 13, tasks.get(1).digest(), TaskStatus.FAIL, -1.0, 0, 0));

    assertThat(kit.getState().status()).isEqualTo(DocumentStatus.FAIL);
    assertThat(kit.getState().progress()).isEqualTo(-1.0);
  }

  // row 15, review-findings.md: a re-parse must wipe the prior generation's task summaries, or
  // a range that reuses a completed task (and so never re-reports its chunk/token counts as a
  // fresh event) leaves the stale generation's counts sitting in the rollup sum forever.
  @Test
  void aReparseDoesNotDoubleCountChunksFromASupersededGeneration() {
    var kit = kit();
    List<String> pages = new java.util.ArrayList<>();
    for (int i = 0; i < 24; i++) {
      pages.add("p" + i);
    }
    kit.method(DocumentEntity::create)
        .invoke(new DocumentCommand.Create("kb-1", "doc.pdf", FileType.PDF, pages, ParserConfig.defaults()));
    var gen1 = kit.method(DocumentEntity::parse).invoke().getReply();
    assertThat(gen1).hasSize(2); // 24 pages / taskPageSize 12

    // Task A finishes; task B is left mid-flight, still RUNNING.
    kit.method(DocumentEntity::reportTaskProgress)
        .invoke(new DocumentCommand.ReportTaskProgress(gen1.get(0).taskId(), 0, 12, gen1.get(0).digest(), TaskStatus.DONE, 1.0, 5, 50));
    kit.method(DocumentEntity::reportTaskProgress)
        .invoke(new DocumentCommand.ReportTaskProgress(gen1.get(1).taskId(), 12, 24, gen1.get(1).digest(), TaskStatus.RUNNING, 0.3, 0, 0));

    // Re-parse: A's range is reused (its gen-1 task finished); B's range is not (gen-1's B never
    // finished), so B gets a genuinely fresh task.
    var gen2 = kit.method(DocumentEntity::parse).invoke().getReply();
    assertThat(gen2.get(0).reuseFromTaskId()).isEqualTo(gen1.get(0).taskId());
    assertThat(gen2.get(1).reuseFromTaskId()).isNull();

    // Both generation-2 tasks eventually report their real counts.
    kit.method(DocumentEntity::reportTaskProgress)
        .invoke(new DocumentCommand.ReportTaskProgress(gen2.get(0).taskId(), 0, 12, gen2.get(0).digest(), TaskStatus.DONE, 1.0, 5, 50));
    kit.method(DocumentEntity::reportTaskProgress)
        .invoke(new DocumentCommand.ReportTaskProgress(gen2.get(1).taskId(), 12, 24, gen2.get(1).digest(), TaskStatus.DONE, 1.0, 3, 30));

    assertThat(kit.getState().status()).isEqualTo(DocumentStatus.DONE);
    assertThat(kit.getState().chunkNum()).isEqualTo(8); // 5 + 3, not 5 + 0 + 5 + 3 from a stale gen-1 A
    assertThat(kit.getState().tokenNum()).isEqualTo(80);
  }

  @Test
  void cancellationIsNeverOverwrittenByALateProgressReport() {
    var kit = kit();
    kit.method(DocumentEntity::create)
        .invoke(new DocumentCommand.Create("kb-1", "doc.txt", FileType.TEXT, List.of("hello"), ParserConfig.defaults()));
    var tasks = kit.method(DocumentEntity::parse).invoke().getReply();

    kit.method(DocumentEntity::cancel).invoke();
    assertThat(kit.getState().status()).isEqualTo(DocumentStatus.CANCEL);

    kit.method(DocumentEntity::reportTaskProgress)
        .invoke(new DocumentCommand.ReportTaskProgress(tasks.get(0).taskId(), 0, 0, tasks.get(0).digest(), TaskStatus.DONE, 1.0, 3, 30));
    assertThat(kit.getState().status()).isEqualTo(DocumentStatus.CANCEL);
  }
}
