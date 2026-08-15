package io.github.voraes.jscheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Structured scheduler event. */
public sealed interface JobEvent permits JobEvent.JobScheduled, JobEvent.JobStarted,
        JobEvent.JobSucceeded, JobEvent.JobFailed, JobEvent.JobRetryScheduled,
        JobEvent.JobSkipped, JobEvent.JobTimedOut, JobEvent.JobCancelled,
        JobEvent.JobRateLimited, JobEvent.CircuitOpened, JobEvent.CircuitClosed {

    Context context();

    record Context(JobId jobId, String jobName, long sequence, int attempt, Instant occurredAt) {
        public Context {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(jobName, "jobName");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (sequence < 1 || attempt < 1) {
                throw new IllegalArgumentException("sequence and attempt must be positive");
            }
        }
    }

    record JobScheduled(Context context, Instant scheduledFor) implements JobEvent {
        public JobScheduled {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(scheduledFor, "scheduledFor");
        }
    }

    record JobStarted(Context context) implements JobEvent {
        public JobStarted {
            Objects.requireNonNull(context, "context");
        }
    }

    record JobSucceeded(Context context, Duration duration) implements JobEvent {
        public JobSucceeded {
            requireDuration(context, duration);
        }
    }

    record JobFailed(Context context, Duration duration, Throwable failure) implements JobEvent {
        public JobFailed {
            requireDuration(context, duration);
            Objects.requireNonNull(failure, "failure");
        }
    }

    record JobRetryScheduled(Context context, int nextAttempt, Duration delay,
            Throwable failure) implements JobEvent {
        public JobRetryScheduled {
            Objects.requireNonNull(context, "context");
            if (nextAttempt < 2) {
                throw new IllegalArgumentException("nextAttempt must be at least 2");
            }
            requireNonNegative(delay, "delay");
            Objects.requireNonNull(failure, "failure");
        }
    }

    record JobSkipped(Context context, String reason) implements JobEvent {
        public JobSkipped {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason must not be blank");
            }
        }
    }

    record JobTimedOut(Context context, Duration timeout) implements JobEvent {
        public JobTimedOut {
            Objects.requireNonNull(context, "context");
            requirePositive(timeout, "timeout");
        }
    }

    record JobCancelled(Context context, boolean interruptionRequested) implements JobEvent {
        public JobCancelled {
            Objects.requireNonNull(context, "context");
        }
    }

    record JobRateLimited(Context context, Duration delay, Optional<String> group)
            implements JobEvent {
        public JobRateLimited {
            Objects.requireNonNull(context, "context");
            requirePositive(delay, "delay");
            Objects.requireNonNull(group, "group");
        }
    }

    record CircuitOpened(Context context, Duration openDuration) implements JobEvent {
        public CircuitOpened {
            Objects.requireNonNull(context, "context");
            requirePositive(openDuration, "openDuration");
        }
    }

    record CircuitClosed(Context context) implements JobEvent {
        public CircuitClosed {
            Objects.requireNonNull(context, "context");
        }
    }

    private static void requireDuration(Context context, Duration duration) {
        Objects.requireNonNull(context, "context");
        requireNonNegative(duration, "duration");
    }

    private static void requirePositive(Duration duration, String name) {
        requireNonNegative(duration, name);
        if (duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
