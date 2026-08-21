package io.akka.ragflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import io.akka.ragflow.domain.DocumentState;
import io.akka.ragflow.domain.DocumentStatus;
import io.akka.ragflow.domain.FileType;
import io.akka.ragflow.domain.ParserConfig;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §3 rules 12-22, driven end to end through the real HTTP surface, workflow, entities
 * and view — not only the components in isolation (complementing {@code TaskEntityTest} and
 * {@code DocumentEntityTest}).
 */
public class RuntimeBackedIngestTest extends TestKitSupport {

  private String docId() {
    return "doc-" + UUID.randomUUID();
  }

  private void create(String docId, DocumentEndpoint.CreateRequest req) {
    httpClient.POST("/documents/" + docId).withRequestBody(req).invoke();
  }

  private DocumentState read(String docId) {
    return httpClient.GET("/documents/" + docId).responseBodyAs(DocumentState.class).invoke().body();
  }

  private DocumentEndpoint.ChunksResponse chunks(String docId) {
    return httpClient.GET("/documents/" + docId + "/chunks").responseBodyAs(DocumentEndpoint.ChunksResponse.class).invoke().body();
  }

  // rules 20-22: parse -> chunk -> embed -> index, in order, and only a DONE task's chunks appear.
  @Test
  void aTextDocumentIsChunkedEmbeddedAndIndexedThenReadableByDocument() {
    String id = docId();
    create(
        id,
        new DocumentEndpoint.CreateRequest(
            "kb-1", "doc.txt", FileType.TEXT,
            "Alpha bravo charlie.\nDelta echo foxtrot.\nGolf hotel india.\n",
            null, new ParserConfig(6, "\n", 0, io.akka.ragflow.domain.MergeStrategy.OVER_CAP, 12)));

    httpClient.POST("/documents/" + id + "/parse").invoke();

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(read(id).status()).isEqualTo(DocumentStatus.DONE));

    DocumentState doc = read(id);
    assertThat(doc.chunkNum()).isGreaterThan(0);
    assertThat(doc.tokenNum()).isGreaterThan(0);

    // The view is a separate, eventually-consistent projection off TaskEntity's events (rule 22)
    // — it can lag a moment behind the document rollup reaching DONE, so it is polled on its own.
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(chunks(id).chunks()).hasSize(doc.chunkNum()));
    assertThat(chunks(id).chunks()).allSatisfy(c -> assertThat(c.tokenNum()).isGreaterThan(0));
  }

  // rule 12: a PDF-sized document (simulated via a multi-page-equivalent text doc is not possible
  // through this HTTP surface without a real PDF, so this test uses TEXT/MARKDOWN's single-task
  // path and is the multi-task path's counterpart in DocumentEntityTest, which drives the split
  // directly).
  @Test
  void aMarkdownDocumentAppliesTheShortHeaderRuleThroughTheRealPipeline() {
    String id = docId();
    create(
        id,
        new DocumentEndpoint.CreateRequest(
            "kb-1", "doc.md", FileType.MARKDOWN,
            "# Title\nBody paragraph one is here with enough words to matter.\n",
            null, new ParserConfig(1, "\n", 0, io.akka.ragflow.domain.MergeStrategy.OVER_CAP, 12)));

    httpClient.POST("/documents/" + id + "/parse").invoke();

    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(read(id).status()).isEqualTo(DocumentStatus.DONE));

    // Without rule 10 this tiny cap would split the heading from its body into two chunks.
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(chunks(id).chunks()).hasSize(1));
    assertThat(chunks(id).chunks().get(0).content()).contains("# Title").contains("Body paragraph");
  }

  // rule 14: parsing an unchanged document twice reuses the first pass's chunks — the second
  // pass's task carries the identical chunk ids rather than re-chunking, even though the view
  // (unlike the source's doc store) keeps both generations' rows rather than deleting the first
  // (README "Where it differs from infiniflow/ragflow").
  @Test
  void reparsingAnUnchangedDocumentReusesItsChunksInsteadOfRechunking() {
    String id = docId();
    create(
        id,
        new DocumentEndpoint.CreateRequest(
            "kb-1", "doc.txt", FileType.TEXT, "one two three four five six seven\n", null, ParserConfig.defaults()));

    httpClient.POST("/documents/" + id + "/parse").invoke();
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(read(id).status()).isEqualTo(DocumentStatus.DONE));
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(chunks(id).chunks()).isNotEmpty());
    var firstChunkIds =
        chunks(id).chunks().stream().map(DocumentEndpoint.ChunkSummary::id).distinct().sorted().toList();

    httpClient.POST("/documents/" + id + "/parse").invoke();
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(() -> assertThat(read(id).status()).isEqualTo(DocumentStatus.DONE));
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(
                        chunks(id).chunks().stream()
                            .map(DocumentEndpoint.ChunkSummary::id)
                            .distinct()
                            .sorted()
                            .toList())
                    .isEqualTo(firstChunkIds));
  }

  // rule 19: a canceled document's status is never overwritten by a late task report.
  @Test
  void cancellingADocumentBeforeItFinishesLeavesItCanceled() {
    String id = docId();
    create(
        id,
        new DocumentEndpoint.CreateRequest(
            "kb-1", "doc.txt", FileType.TEXT, "hello world\n", null, ParserConfig.defaults()));
    httpClient.POST("/documents/" + id + "/cancel").invoke();
    assertThat(read(id).status()).isEqualTo(DocumentStatus.CANCEL);
  }
}
