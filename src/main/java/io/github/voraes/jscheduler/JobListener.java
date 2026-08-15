package io.github.voraes.jscheduler;

/** Receives lifecycle snapshots. Listener failures are isolated from scheduler operation. */
@FunctionalInterface
public interface JobListener {
    void onTransition(JobId jobId, JobExecution execution);
}
