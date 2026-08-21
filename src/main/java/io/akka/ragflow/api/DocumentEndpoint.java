package io.akka.ragflow.api;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpResponses;
import io.akka.ragflow.application.ChunkView;
import io.akka.ragflow.application.DocumentEntity;
import io.akka.ragflow.application.IngestWorkflow;
import io.akka.ragflow.application.PdfTextExtractor;
import io.akka.ragflow.application.TaskEntity;
import io.akka.ragflow.domain.DocumentCommand;
import io.akka.ragflow.domain.DocumentState;
import io.akka.ragflow.domain.FileType;
import io.akka.ragflow.domain.ParserConfig;
import io.akka.ragflow.domain.TaskCommand;
import io.akka.ragflow.domain.TaskDescriptor;
import java.util.Base64;
import java.util.List;

/**
 * A document's lifecycle as an outside caller reaches it (SPEC-001 §1-§3): create, kick off
 * ingestion, read status, read the chunks it produced, cancel.
 *
 * <p>Task splitting (rule 12) happens inside {@link DocumentEntity#parse}; what happens here is
 * only what the source's own upload/parse endpoint does outside the task-splitting decision
 * itself — queuing each resulting task and starting its pipeline (question-log row 8's
 * evidence, {@code api/apps/restful_apis/document_api.py:1549-1662}).
 */
@HttpEndpoint("/documents")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class DocumentEndpoint {

  public record CreateRequest(
      String kbId, String name, FileType fileType, String text, String pdfBase64, ParserConfig parserConfig) {}

  public record ChunkSummary(String id, int tokenNum, List<Integer> pages, String content) {}

  public record ChunksResponse(String docId, List<ChunkSummary> chunks) {}

  private final ComponentClient componentClient;

  public DocumentEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Post("/{docId}")
  public HttpResponse create(String docId, CreateRequest req) {
    List<String> sections;
    if (req.fileType() == FileType.PDF) {
      byte[] bytes = Base64.getDecoder().decode(req.pdfBase64());
      sections = PdfTextExtractor.extractPages(bytes);
    } else {
      sections = List.of(req.text() == null ? "" : req.text());
    }
    ParserConfig config = req.parserConfig() != null ? req.parserConfig() : ParserConfig.defaults();
    componentClient
        .forEventSourcedEntity(docId)
        .method(DocumentEntity::create)
        .invoke(new DocumentCommand.Create(req.kbId(), req.name(), req.fileType(), sections, config));
    return HttpResponses.created();
  }

  @Post("/{docId}/parse")
  public List<TaskDescriptor> parse(String docId) {
    List<TaskDescriptor> tasks =
        componentClient.forEventSourcedEntity(docId).method(DocumentEntity::parse).invoke();
    for (TaskDescriptor t : tasks) {
      if (t.reuseFromTaskId() == null) {
        componentClient
            .forEventSourcedEntity(t.taskId())
            .method(TaskEntity::queue)
            .invoke(new TaskCommand.Queue(docId, t.fromPage(), t.toPage(), t.digest()));
      }
      componentClient
          .forWorkflow(t.taskId())
          .method(IngestWorkflow::start)
          .invoke(new IngestWorkflow.StartCommand(docId, t.fromPage(), t.toPage(), t.digest(), t.reuseFromTaskId()));
    }
    return tasks;
  }

  @Post("/{docId}/cancel")
  public HttpResponse cancel(String docId) {
    componentClient.forEventSourcedEntity(docId).method(DocumentEntity::cancel).invoke();
    return HttpResponses.ok();
  }

  @Get("/{docId}")
  public DocumentState read(String docId) {
    return componentClient.forEventSourcedEntity(docId).method(DocumentEntity::read).invoke();
  }

  @Get("/{docId}/chunks")
  public ChunksResponse chunks(String docId) {
    var result =
        componentClient.forView().method(ChunkView::forDocument).invoke(docId);
    List<ChunkSummary> chunks =
        result.tasks().stream()
            .flatMap(t -> t.chunks().stream())
            .map(c -> new ChunkSummary(c.id(), c.tokenNum(), c.pages(), c.content()))
            .toList();
    return new ChunksResponse(docId, chunks);
  }
}
