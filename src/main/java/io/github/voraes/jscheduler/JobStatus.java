package io.github.voraes.jscheduler;

/** The observable lifecycle state of a job. */
public enum JobStatus {
    SCHEDULED,
    READY,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    SKIPPED
}
