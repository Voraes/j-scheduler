package io.github.voraes.jscheduler;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Thread-safe, read-only access to a scheduled job plus cancellation. */
public interface JobHandle {
    JobId id();

    String name();

    JobStatus status();

    Optional<Instant> nextExecution();

    Optional<JobExecution> latestExecution();

    /**
     * Completes when this handle reaches a final state. Recurring jobs complete only after
     * cancellation or scheduler shutdown.
     */
    CompletionStage<JobExecution> completion();

    /** Returns the current circuit state when this job has a circuit breaker. */
    Optional<CircuitState> circuitState();

    default boolean cancel() {
        return cancel(false);
    }

    /**
     * Cancels future occurrences. If requested, interruption is also sent to running occurrences;
     * user code must cooperate with interruption.
     */
    boolean cancel(boolean mayInterruptIfRunning);
}
