package io.github.voraes.jscheduler;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** A snapshot describing one occurrence of a scheduled job. */
public record JobExecution(long sequence, int attempt, Instant scheduledFor, JobStatus status,
        Optional<JobResult> result) {
    public JobExecution {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        Objects.requireNonNull(scheduledFor, "scheduledFor");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(result, "result");
    }
}
