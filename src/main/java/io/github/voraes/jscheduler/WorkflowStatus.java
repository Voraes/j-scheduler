package io.github.voraes.jscheduler;

/** Aggregate workflow lifecycle state. */
public enum WorkflowStatus {
    SCHEDULED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
