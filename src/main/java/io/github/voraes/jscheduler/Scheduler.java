package io.github.voraes.jscheduler;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * A thread-safe embedded scheduler with explicit execution and shutdown bounds.
 *
 * <p>Time controls eligibility; priority orders only work that is already due. All methods may be
 * called concurrently. Implementations created by {@link #builder()} own their worker, coordinator,
 * timeout, and optional listener threads until shutdown.
 */
public interface Scheduler extends AutoCloseable {
    /** Returns a new builder using bounded platform threads by default. */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Schedules a job according to the immutable schedule.
     *
     * @throws RejectedExecutionException when shutdown has begun
     */
    JobHandle schedule(Job job, Schedule schedule) throws RejectedExecutionException;

    /** Schedules a validated workflow immediately. */
    WorkflowHandle schedule(Workflow workflow) throws RejectedExecutionException;

    /** Schedules a job for immediate eligibility. */
    default JobHandle execute(Job job) {
        return schedule(job, Schedule.immediate());
    }

    /** Immediately rejects new work, removes queued work, and interrupts running work. */
    void shutdown();

    /**
     * Rejects new work, cancels not-yet-due work, and lets ready and running work finish. On timeout,
     * queued work is cancelled and running work is interrupted.
     *
     * @return {@code true} when all executing work finished before the timeout
     */
    boolean shutdownGracefully(Duration timeout) throws InterruptedException;

    /** Returns whether new work is rejected. */
    boolean isShutdown();

    /** Returns whether scheduler infrastructure has stopped and no execution remains active. */
    boolean isTerminated();

    /** Returns an immutable snapshot without exposing job payloads or mutable queues. */
    SchedulerSnapshot snapshot();

    /** Registers an explicit JVM hook. The returned hook can be removed by the caller if needed. */
    Thread registerShutdownHook();

    @Override
    default void close() {
        try {
            shutdownGracefully(Duration.ofSeconds(30));
        } catch (InterruptedException interrupted) {
            shutdown();
            Thread.currentThread().interrupt();
        }
    }

    /** Mutable, single-use-style configuration builder; it is not thread-safe. */
    final class Builder {
        private ExecutionMode mode = ExecutionMode.PLATFORM;
        private int maxConcurrentJobs = Math.max(1, Runtime.getRuntime().availableProcessors());
        private Clock clock = Clock.systemUTC();
        private JobListener listener;
        private final List<JobEventListener> eventListeners = new ArrayList<>();

        private Builder() { }

        /** Selects a bounded reusable platform-thread pool. */
        public Builder platformThreads(int threadCount) {
            if (threadCount < 1) {
                throw new IllegalArgumentException("threadCount must be positive");
            }
            mode = ExecutionMode.PLATFORM;
            maxConcurrentJobs = threadCount;
            return this;
        }

        /** Selects one virtual thread per admitted task. Configure its bound separately. */
        public Builder virtualThreads() {
            mode = ExecutionMode.VIRTUAL;
            return this;
        }

        /** Sets the maximum simultaneous tasks in virtual-thread mode. */
        public Builder maxConcurrentJobs(int maximum) {
            if (maximum < 1) {
                throw new IllegalArgumentException("maximum must be positive");
            }
            maxConcurrentJobs = maximum;
            return this;
        }

        /** Sets the wall clock used for public lifecycle timestamps. */
        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /** Sets the legacy snapshot listener. Prefer structured event listeners for new code. */
        public Builder listener(JobListener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        /** Adds an ordered asynchronous structured-event listener. */
        public Builder eventListener(JobEventListener listener) {
            eventListeners.add(Objects.requireNonNull(listener, "listener"));
            return this;
        }

        /** Builds and starts an independent scheduler instance. */
        public Scheduler build() {
            return new DefaultScheduler(mode, maxConcurrentJobs, clock, listener,
                    List.copyOf(eventListeners));
        }
    }
}
