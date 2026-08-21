package io.akka.ragflow.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * A deterministic stand-in for the source's embedding-model call (SPEC-001 §4 decision 3). Same
 * content always produces the same vector; there is no network call and no claim of semantic
 * meaning. This exists so the pipeline-stage-ordering rules (SPEC-001 §3 rules 20-22) have a real
 * vector to depend on, without this port running a language model.
 */
public final class Embedder {

  public static final int DIMENSIONS = 16;

  private Embedder() {}

  public static List<Double> embed(String content) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      byte[] hash = sha256.digest(content.getBytes(StandardCharsets.UTF_8));
      List<Double> vector = new ArrayList<>(DIMENSIONS);
      for (int i = 0; i < DIMENSIONS; i++) {
        int b = hash[i % hash.length] & 0xFF;
        vector.add((b / 255.0) * 2.0 - 1.0);
      }
      return vector;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
