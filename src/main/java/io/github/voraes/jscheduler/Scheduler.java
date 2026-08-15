package io.github.voraes.jscheduler;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

/**
 * A thread-safe embedded scheduler. Due work is ordered by priority; priority never makes future
 * work eligible early.
 */
public interface Scheduler extends AutoCloseable {
    static Builder builder() {
        return new Builder();
    }

    JobHandle schedule(Job job, Schedule schedule) throws RejectedExecutionException;

    /** Schedules a validated workflow immediately. */
    WorkflowHandle schedule(Workflow workflow) throws RejectedExecutionException;

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

    boolean isShutdown();

    boolean isTerminated();

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

    final class Builder {
        private ExecutionMode mode = ExecutionMode.PLATFORM;
        private int maxConcurrentJobs = Math.max(1, Runtime.getRuntime().availableProcessors());
        private Clock clock = Clock.systemUTC();
        private JobListener listener;
        private final List<JobEventListener> eventListeners = new ArrayList<>();

        private Builder() { }

        public Builder platformThreads(int threadCount) {
            if (threadCount < 1) {
                throw new IllegalArgumentException("threadCount must be positive");
            }
            mode = ExecutionMode.PLATFORM;
            maxConcurrentJobs = threadCount;
            return this;
        }

        public Builder virtualThreads() {
            mode = ExecutionMode.VIRTUAL;
            return this;
        }

        public Builder maxConcurrentJobs(int maximum) {
            if (maximum < 1) {
                throw new IllegalArgumentException("maximum must be positive");
            }
            maxConcurrentJobs = maximum;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public Builder listener(JobListener listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            return this;
        }

        public Builder eventListener(JobEventListener listener) {
            eventListeners.add(Objects.requireNonNull(listener, "listener"));
            return this;
        }

        public Scheduler build() {
            return new DefaultScheduler(mode, maxConcurrentJobs, clock, listener,
                    List.copyOf(eventListeners));
        }
    }
}
