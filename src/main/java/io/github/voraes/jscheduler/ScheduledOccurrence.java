package io.github.voraes.jscheduler;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

final class ScheduledOccurrence {
    final ScheduledJob owner;
    final long sequence;
    final int attempt;
    final long order;
    long deadlineNanos;
    final Instant scheduledFor;
    Instant eligibleAt;
    ExecutionState state = ExecutionState.SCHEDULED;
    JobExecution snapshot;
    Instant startedAt;
    volatile Thread executingThread;
    ScheduledFuture<?> timeoutFuture;
    JobResult timeoutResult;
    boolean timedOut;
    boolean cancelRequested;
    boolean cancellationEventPublished;

    ScheduledOccurrence(ScheduledJob owner, long sequence, int attempt, long order,
            long deadlineNanos, Instant scheduledFor, Instant eligibleAt) {
        this.owner = owner;
        this.sequence = sequence;
        this.attempt = attempt;
        this.order = order;
        this.deadlineNanos = deadlineNanos;
        this.scheduledFor = scheduledFor;
        this.eligibleAt = eligibleAt;
        snapshot = new JobExecution(sequence, attempt, scheduledFor, JobStatus.SCHEDULED,
                Optional.empty());
    }
}
