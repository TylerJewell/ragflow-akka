package io.akka.ragflow.domain;

import java.util.List;

/**
 * One indexed unit — the terminal record of the ingestion pipeline (SPEC-001 §2, §3 rules 20-22).
 *
 * <p>{@code vector} is a deterministic stand-in embedding (SPEC-001 §4 decision 3), not a claim
 * about semantic similarity.
 */
public record Chunk(
    String id,
    String docId,
    String taskId,
    String kbId,
    String content,
    int tokenNum,
    List<Integer> pages,
    List<Double> vector) {}
