package io.github.voraes.jscheduler;

/** Immutable, payload-free scheduler statistics suitable for health and metrics integrations. */
public record SchedulerSnapshot(SchedulerStatus status, ExecutionMode executionMode,
        int maxConcurrentJobs, int trackedJobs, int scheduledOccurrences, int readyOccurrences,
        int runningOccurrences) {
    public SchedulerSnapshot {
        java.util.Objects.requireNonNull(status, "status");
        java.util.Objects.requireNonNull(executionMode, "executionMode");
        if (maxConcurrentJobs < 1 || trackedJobs < 0 || scheduledOccurrences < 0 || readyOccurrences < 0
                || runningOccurrences < 0) {
            throw new IllegalArgumentException(
                    "Scheduler capacity must be positive and counts must not be negative");
        }
    }
}
