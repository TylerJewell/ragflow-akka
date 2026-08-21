package io.akka.ragflow.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import io.akka.ragflow.domain.Chunker;
import io.akka.ragflow.domain.ParserConfig;
import java.util.List;

/**
 * Exposes {@link Chunker#naiveMerge} directly over HTTP, with no document, task, or workflow
 * around it — so {@code bench/} can compare this port's merge algorithm against the source's
 * {@code rag.nlp.naive_merge} on the same input, the same way {@code metaflow-port/bench/}
 * compares its port by hitting a running HTTP surface rather than calling internal classes
 * (SPEC-001 is silent on benchmarking; this endpoint exists only for that comparison).
 */
@HttpEndpoint("/bench")
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
public class BenchEndpoint {

  public record ChunkRequest(String text, ParserConfig parserConfig, boolean markdown) {}

  public record ChunkDraftView(String content, int tokenNum, List<Integer> pages) {}

  @Post("/chunk")
  public List<ChunkDraftView> chunk(ChunkRequest req) {
    var drafts =
        Chunker.naiveMerge(
            List.of(new Chunker.Section(req.text(), null)), req.parserConfig(), req.markdown());
    return drafts.stream()
        .map(d -> new ChunkDraftView(d.content(), d.tokenNum(), d.pages()))
        .toList();
  }
}
