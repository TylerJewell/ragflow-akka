package io.akka.ragflow.domain;

/** SPEC-001 §2 Document.status, ported from the source's {@code TaskStatus} enum (question-log
 * row 7: {@code common/constants.py:106-115}) minus the unused {@code SCHEDULE} value, which the
 * source itself never assigns to a Document. */
public enum DocumentStatus {
  UNSTART,
  RUNNING,
  CANCEL,
  DONE,
  FAIL
}
