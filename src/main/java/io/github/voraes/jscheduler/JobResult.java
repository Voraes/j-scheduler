package io.github.voraes.jscheduler;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Immutable result of one execution attempt. */
public record JobResult(JobStatus status, Optional<Instant> startedAt, Instant completedAt,
        Optional<Throwable> failure) {
    public JobResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(failure, "failure");
        if (status != JobStatus.SUCCEEDED && status != JobStatus.FAILED
                && status != JobStatus.CANCELLED && status != JobStatus.TIMED_OUT
                && status != JobStatus.SKIPPED) {
            throw new IllegalArgumentException("Result status must be terminal");
        }
        if ((status == JobStatus.FAILED || status == JobStatus.TIMED_OUT) != failure.isPresent()) {
            throw new IllegalArgumentException("Failed and timed-out results must contain a failure");
        }
        if ((status == JobStatus.SUCCEEDED || status == JobStatus.FAILED
                || status == JobStatus.TIMED_OUT) && startedAt.isEmpty()) {
            throw new IllegalArgumentException("An executed result must contain its start time");
        }
    }

}
