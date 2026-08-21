package io.akka.ragflow.domain;

/**
 * How {@link Chunker} groups delimiter-split paragraphs into chunks (SPEC-001 §3 rule 7), ported
 * from {@code rag/nlp/__init__.py}'s {@code MergeStrategy}.
 *
 * <p>{@code OVER_CAP} (the source's default) closes a chunk once its running total already
 * exceeds the threshold — the paragraph that tipped it over stays in the closed chunk. {@code
 * UNDER_CAP} never lets a chunk's running total exceed the cap in the first place.
 */
public enum MergeStrategy {
  OVER_CAP,
  UNDER_CAP
}
