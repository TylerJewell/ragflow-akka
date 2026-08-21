package io.akka.ragflow.domain;

/**
 * One task {@code parse} creates, returned to the caller so it can queue the {@code TaskEntity}
 * and start its {@code IngestWorkflow} (SPEC-001 §3 rules 12-14). {@code reuseFromTaskId} is
 * non-null exactly when {@link DocumentState#findReusable} matched.
 */
public record TaskDescriptor(
    String taskId, int fromPage, int toPage, String digest, String reuseFromTaskId) {}
