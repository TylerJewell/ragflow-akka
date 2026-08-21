package io.akka.ragflow.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A task's re-index-trigger digest (SPEC-001 §3 rule 13), ported from {@code queue_tasks} in
 * {@code api/db/services/task_service.py:507-519} (question-log row 9).
 *
 * <p>The source hashes with xxhash64; this port uses SHA-256 over the same inputs instead (SPEC-001
 * §4 decision 5) — nothing in the contract depends on the two systems producing byte-identical
 * digests, only on one system's digest being stable and configuration-derived.
 */
public final class TaskDigest {

  private TaskDigest() {}

  public static String of(ParserConfig config, String docId, int fromPage, int toPage) {
    String input = config.digestFields() + ";docId=" + docId + ";fromPage=" + fromPage + ";toPage=" + toPage;
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      byte[] hash = sha256.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
