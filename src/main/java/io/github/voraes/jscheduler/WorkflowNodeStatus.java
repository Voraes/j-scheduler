package io.github.voraes.jscheduler;

/** Lifecycle state of one workflow node. */
public enum WorkflowNodeStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    CANCELLED
}
