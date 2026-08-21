package io.akka.ragflow.domain;

/**
 * SPEC-001 §2 Task.status. There is no separate "queued but not yet dispatched" value: the
 * source itself never distinguishes that from RUNNING for a single task (only Document
 * separates UNSTART from RUNNING), so {@code queue()} moves a task straight to RUNNING.
 */
public enum TaskStatus {
  RUNNING,
  DONE,
  FAIL,
  CANCEL
}
