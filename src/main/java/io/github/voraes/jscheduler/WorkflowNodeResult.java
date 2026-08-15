package io.github.voraes.jscheduler;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Immutable final result for one workflow node. */
public record WorkflowNodeResult(String node, WorkflowNodeStatus status,
        Optional<JobExecution> execution, Optional<Instant> startedAt, Instant completedAt) {
    public WorkflowNodeResult {
        Objects.requireNonNull(node, "node");
        if (node.isBlank()) {
            throw new IllegalArgumentException("node must not be blank");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        if (status == WorkflowNodeStatus.PENDING || status == WorkflowNodeStatus.RUNNING) {
            throw new IllegalArgumentException("Workflow node result must be terminal");
        }
    }
}
