package io.github.voraes.jscheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Immutable user work and its scheduling metadata. */
public final class Job {
    private final String name;
    private final Runnable task;
    private final int priority;
    private final RetryPolicy retryPolicy;
    private final Duration timeout;
    private final ConcurrencyPolicy concurrencyPolicy;
    private final RateLimit rateLimit;
    private final String rateLimitGroup;
    private final CircuitBreakerPolicy circuitBreakerPolicy;

    private Job(Builder builder) {
        this.name = builder.name;
        this.task = builder.task;
        this.priority = builder.priority;
        this.retryPolicy = builder.retryPolicy;
        this.timeout = builder.timeout;
        this.concurrencyPolicy = builder.concurrencyPolicy;
        this.rateLimit = builder.rateLimit;
        this.rateLimitGroup = builder.rateLimitGroup;
        this.circuitBreakerPolicy = builder.circuitBreakerPolicy;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public Runnable task() {
        return task;
    }

    public int priority() {
        return priority;
    }

    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    public ConcurrencyPolicy concurrencyPolicy() {
        return concurrencyPolicy;
    }

    public Optional<RateLimit> rateLimit() {
        return Optional.ofNullable(rateLimit);
    }

    public Optional<String> rateLimitGroup() {
        return Optional.ofNullable(rateLimitGroup);
    }

    public Optional<CircuitBreakerPolicy> circuitBreakerPolicy() {
        return Optional.ofNullable(circuitBreakerPolicy);
    }

    public static final class Builder {
        private final String name;
        private Runnable task;
        private int priority;
        private RetryPolicy retryPolicy = RetryPolicy.none();
        private Duration timeout;
        private ConcurrencyPolicy concurrencyPolicy = ConcurrencyPolicy.ALLOW;
        private RateLimit rateLimit;
        private String rateLimitGroup;
        private CircuitBreakerPolicy circuitBreakerPolicy;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Job name must not be blank");
            }
        }

        public Builder task(Runnable task) {
            this.task = Objects.requireNonNull(task, "task");
            return this;
        }

        /** Sets priority; larger values run before smaller values once both jobs are ready. */
        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Builder retry(RetryPolicy retryPolicy) {
            this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
            return this;
        }

        /** Sets a cooperative timeout. The task must respond to interruption. */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        public Builder concurrency(ConcurrencyPolicy policy) {
            concurrencyPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /** Applies a rate limit to this job only. */
        public Builder rateLimit(RateLimit rateLimit) {
            this.rateLimit = Objects.requireNonNull(rateLimit, "rateLimit");
            this.rateLimitGroup = null;
            return this;
        }

        /** Applies a shared named rate limit. Jobs in a group must use identical configuration. */
        public Builder rateLimit(String group, RateLimit rateLimit) {
            Objects.requireNonNull(group, "group");
            if (group.isBlank()) {
                throw new IllegalArgumentException("rate-limit group must not be blank");
            }
            this.rateLimitGroup = group;
            this.rateLimit = Objects.requireNonNull(rateLimit, "rateLimit");
            return this;
        }

        public Builder circuitBreaker(CircuitBreakerPolicy policy) {
            circuitBreakerPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        public Job build() {
            if (task == null) {
                throw new IllegalStateException("A task is required");
            }
            return new Job(this);
        }
    }
}
