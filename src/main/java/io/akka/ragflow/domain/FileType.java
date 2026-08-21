package io.akka.ragflow.domain;

/** Which parser runs (SPEC-001 §2, §1 scope: only these three input shapes are ported). */
public enum FileType {
  TEXT,
  MARKDOWN,
  PDF
}
