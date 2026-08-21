package io.akka.ragflow.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The naive/general chunker's merge algorithm (SPEC-001 §3 rules 5-11), ported from {@code
 * naive_merge} / {@code merge_paragraphs} / {@code _merge_paragraph_groups} in {@code
 * rag/nlp/__init__.py}.
 *
 * <p>Pure and deterministic: no I/O, no PDF coordinates. {@link #naiveMerge} is the single entry
 * point both the text/Markdown and PDF parsing paths in {@code application} feed into.
 */
public final class Chunker {

  private static final Pattern SHORT_HEADER = Pattern.compile("^#{1,6}\\s+");
  private static final int SHORT_HEADER_MAX_TOKENS = 50;

  private Chunker() {}

  /** One unit of extracted text before delimiter splitting — a whole file, or one PDF page. */
  public record Section(String text, Integer page) {}

  private record Paragraph(String text, Integer page) {}

  /** One merged, overlap-applied chunk, ready to be embedded and indexed. */
  public record ChunkDraft(String content, int tokenNum, List<Integer> pages) {}

  /**
   * Splits {@code sections} on the configured delimiter and merges the result into chunks per
   * {@code config.strategy()}, applying overlap (rule 9) unless the delimiter field is in
   * "custom" (backtick) mode (rule 6), and, for Markdown, force-merging short headers into the
   * following paragraph before grouping (rule 10).
   */
  public static List<ChunkDraft> naiveMerge(
      List<Section> sections, ParserConfig config, boolean markdownShortHeaderRule) {
    if (sections.isEmpty()) {
      return List.of();
    }

    List<String> parsedDelimiters = Delimiter.parseField(config.delimiter());
    boolean customMode = Delimiter.hasWrappedDelimiter(config.delimiter());
    String patternString = Delimiter.compilePattern(parsedDelimiters);
    // Compiled once per naiveMerge call rather than once per section: a multi-page PDF task
    // otherwise recompiles the identical delimiter regex once per page for no reason.
    Pattern compiled = patternString.isEmpty() ? null : Pattern.compile(patternString, Pattern.DOTALL);

    if (customMode) {
      List<ChunkDraft> out = new ArrayList<>();
      for (Section section : sections) {
        String text = Delimiter.normalizeNewlines(section.text());
        for (String piece : splitOnDelimiters(compiled, text)) {
          String content = "\n" + piece;
          out.add(
              new ChunkDraft(
                  content, TokenCounter.count(content), pagesOf(section.page())));
        }
      }
      return out; // rule 6: no token budget, and no overlap in custom mode.
    }

    List<Paragraph> paragraphs = new ArrayList<>();
    for (Section section : sections) {
      String text = Delimiter.normalizeNewlines(section.text());
      if (compiled == null) {
        paragraphs.add(new Paragraph("\n" + text, section.page()));
        continue;
      }
      for (String piece : splitOnDelimiters(compiled, text)) {
        paragraphs.add(new Paragraph("\n" + piece, section.page()));
      }
    }

    if (markdownShortHeaderRule) {
      paragraphs = forceMergeShortHeaders(paragraphs);
    }

    List<List<Integer>> groups =
        mergeParagraphGroups(paragraphs, config.chunkTokenNum(), config.strategy(), config.overlappedPercent());

    List<ChunkDraft> chunks = new ArrayList<>();
    for (List<Integer> group : groups) {
      StringBuilder sb = new StringBuilder();
      LinkedHashSet<Integer> pages = new LinkedHashSet<>();
      for (int idx : group) {
        Paragraph p = paragraphs.get(idx);
        sb.append(p.text());
        if (p.page() != null) {
          pages.add(p.page());
        }
      }
      String content = sb.toString();
      chunks.add(new ChunkDraft(content, TokenCounter.count(content), List.copyOf(pages)));
    }
    return applyOverlap(chunks, config.overlappedPercent());
  }

  /** Non-delimiter pieces of {@code text}, in order; matched delimiter text is discarded. */
  private static List<String> splitOnDelimiters(Pattern compiled, String text) {
    if (compiled == null) {
      return text.isEmpty() ? List.of() : List.of(text);
    }
    List<String> out = new ArrayList<>();
    Matcher m = compiled.matcher(text);
    int last = 0;
    while (m.find()) {
      String piece = text.substring(last, m.start());
      if (!piece.isEmpty()) {
        out.add(piece);
      }
      last = m.end();
    }
    String tail = text.substring(last);
    if (!tail.isEmpty()) {
      out.add(tail);
    }
    return out;
  }

  /** Rule 10: a short Markdown heading is never its own chunk — merge it into what follows. */
  private static List<Paragraph> forceMergeShortHeaders(List<Paragraph> paragraphs) {
    List<Paragraph> out = new ArrayList<>();
    int i = 0;
    while (i < paragraphs.size()) {
      Paragraph p = paragraphs.get(i);
      if (i < paragraphs.size() - 1 && isShortHeader(p.text())) {
        Paragraph next = paragraphs.get(i + 1);
        out.add(new Paragraph(p.text() + next.text(), p.page() != null ? p.page() : next.page()));
        i += 2;
      } else {
        out.add(p);
        i += 1;
      }
    }
    return out;
  }

  private static boolean isShortHeader(String text) {
    if (text == null) {
      return false;
    }
    String stripped = text.strip();
    if (stripped.isEmpty() || !SHORT_HEADER.matcher(stripped).find()) {
      return false;
    }
    return TokenCounter.count(text) < SHORT_HEADER_MAX_TOKENS;
  }

  /** Rule 7-8: {@code _merge_paragraph_groups}, both strategies. Returns index groups. */
  static List<List<Integer>> mergeParagraphGroups(
      List<Paragraph> paragraphs, int chunkTokenNum, MergeStrategy strategy, int overlappedPercent) {
    int n = paragraphs.size();
    List<List<Integer>> groups = new ArrayList<>();
    int cap = chunkTokenNum;
    double threshold = chunkTokenNum * (100 - overlappedPercent) / 100.0;

    if (strategy == MergeStrategy.UNDER_CAP) {
      List<Integer> cur = new ArrayList<>();
      int curTokens = 0;
      for (int i = 0; i < n; i++) {
        int pt = TokenCounter.count(paragraphs.get(i).text());
        if (cur.isEmpty()) {
          cur = new ArrayList<>(List.of(i));
          curTokens = pt;
          if (curTokens > cap) {
            groups.add(cur);
            cur = new ArrayList<>();
            curTokens = 0;
          }
          continue;
        }
        if (curTokens + pt <= cap) {
          cur.add(i);
          curTokens += pt;
        } else {
          groups.add(cur);
          cur = new ArrayList<>(List.of(i));
          curTokens = pt;
          if (curTokens > cap) {
            groups.add(cur);
            cur = new ArrayList<>();
            curTokens = 0;
          }
        }
      }
      if (!cur.isEmpty()) {
        groups.add(cur);
      }
      return groups;
    }

    // OVER_CAP (default).
    List<Integer> cur = new ArrayList<>();
    int curTokens = 0;
    for (int i = 0; i < n; i++) {
      int pt = TokenCounter.count(paragraphs.get(i).text());
      if (pt > cap) {
        if (!cur.isEmpty()) {
          groups.add(cur);
        }
        groups.add(new ArrayList<>(List.of(i)));
        cur = new ArrayList<>();
        curTokens = 0;
        continue;
      }
      if (cur.isEmpty()) {
        cur = new ArrayList<>(List.of(i));
        curTokens = pt;
        continue;
      }
      if (curTokens > threshold) {
        groups.add(cur);
        cur = new ArrayList<>(List.of(i));
        curTokens = pt;
      } else {
        cur.add(i);
        curTokens += pt;
      }
    }
    if (!cur.isEmpty()) {
      groups.add(cur);
    }
    return groups;
  }

  /** Rule 9: unconditional tail-overlap, carved from the already-overlapped previous chunk. */
  private static List<ChunkDraft> applyOverlap(List<ChunkDraft> chunks, int overlappedPercent) {
    if (overlappedPercent <= 0 || chunks.isEmpty()) {
      return chunks;
    }
    List<ChunkDraft> out = new ArrayList<>();
    out.add(chunks.get(0));
    for (int i = 1; i < chunks.size(); i++) {
      String prevVisible = out.get(i - 1).content();
      String overlapText = "";
      if (!prevVisible.isEmpty()) {
        int overlapStart = (int) Math.floor(prevVisible.length() * (100 - overlappedPercent) / 100.0);
        if (overlapStart < prevVisible.length()) {
          overlapText = prevVisible.substring(overlapStart);
        }
      }
      ChunkDraft c = chunks.get(i);
      if (overlapText.isEmpty()) {
        out.add(c);
      } else {
        String content = overlapText + c.content();
        out.add(new ChunkDraft(content, TokenCounter.count(content), c.pages()));
      }
    }
    return out;
  }

  private static List<Integer> pagesOf(Integer page) {
    return page == null ? List.of() : List.of(page);
  }
}
