package io.github.voraes.jscheduler;

import java.time.Duration;
import java.util.Objects;

/** Immutable configuration for a per-job circuit breaker. */
public record CircuitBreakerPolicy(int failureThreshold, Duration openDuration,
        int halfOpenAttempts) {
    public CircuitBreakerPolicy {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be positive");
        }
        Objects.requireNonNull(openDuration, "openDuration");
        if (openDuration.isNegative() || openDuration.isZero()) {
            throw new IllegalArgumentException("openDuration must be positive");
        }
        if (halfOpenAttempts < 1) {
            throw new IllegalArgumentException("halfOpenAttempts must be positive");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int failureThreshold = 5;
        private Duration openDuration = Duration.ofSeconds(30);
        private int halfOpenAttempts = 1;

        private Builder() { }

        public Builder failureThreshold(int threshold) {
            failureThreshold = threshold;
            return this;
        }

        public Builder openDuration(Duration duration) {
            openDuration = duration;
            return this;
        }

        public Builder halfOpenAttempts(int attempts) {
            halfOpenAttempts = attempts;
            return this;
        }

        public CircuitBreakerPolicy build() {
            return new CircuitBreakerPolicy(failureThreshold, openDuration, halfOpenAttempts);
        }
    }
}
