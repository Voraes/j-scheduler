package io.github.voraes.jscheduler;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class ScheduledJob implements JobHandle {
    final JobId id;
    final Job job;
    final Schedule schedule;
    final TokenBucket bucket;
    final CircuitBreaker breaker;
    final Set<ScheduledOccurrence> occurrences = new HashSet<>();
    private final ReentrantLock lock;
    private final Cancellation cancellation;
    private final CompletableFuture<JobExecution> completion = new CompletableFuture<>();
    long nextSequence = 1;
    boolean recurrenceStopped;
    boolean cancelled;
    int activeExecutions;
    Instant nextExecution;
    JobExecution latestExecution;

    ScheduledJob(JobId id, Job job, Schedule schedule, TokenBucket bucket, CircuitBreaker breaker,
            ReentrantLock lock, Cancellation cancellation) {
        this.id = id;
        this.job = job;
        this.schedule = schedule;
        this.bucket = bucket;
        this.breaker = breaker;
        this.lock = lock;
        this.cancellation = cancellation;
    }

    @Override
    public JobId id() {
        return id;
    }

    @Override
    public String name() {
        return job.name();
    }

    @Override
    public JobStatus status() {
        lock.lock();
        try {
            if (cancelled) {
                return JobStatus.CANCELLED;
            }
            if (occurrences.stream().anyMatch(value -> value.timedOut)) {
                return JobStatus.TIMED_OUT;
            }
            if (activeExecutions > 0) {
                return JobStatus.RUNNING;
            }
            if (occurrences.stream().anyMatch(value -> value.state == ExecutionState.READY)) {
                return JobStatus.READY;
            }
            if (nextExecution != null) {
                return JobStatus.SCHEDULED;
            }
            return latestExecution == null ? JobStatus.SCHEDULED : latestExecution.status();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Instant> nextExecution() {
        lock.lock();
        try {
            return Optional.ofNullable(nextExecution);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<JobExecution> latestExecution() {
        lock.lock();
        try {
            return Optional.ofNullable(latestExecution);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CompletionStage<JobExecution> completion() {
        return completion.minimalCompletionStage();
    }

    void complete() {
        if (latestExecution != null) {
            JobExecution terminalExecution = latestExecution;
            Thread.ofVirtual().name("j-scheduler-completion-" + id.value())
                    .start(() -> completion.complete(terminalExecution));
        }
    }

    @Override
    public Optional<CircuitState> circuitState() {
        lock.lock();
        try {
            return breaker == null ? Optional.empty() : Optional.of(breaker.state());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return cancellation.cancel(this, mayInterruptIfRunning);
    }

    @FunctionalInterface
    interface Cancellation {
        boolean cancel(ScheduledJob handle, boolean interrupt);
    }
}
