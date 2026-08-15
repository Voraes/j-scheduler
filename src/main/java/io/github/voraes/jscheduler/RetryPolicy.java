package io.github.voraes.jscheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;

/**
 * Immutable retry configuration. The attempt limit includes the initial execution, and backoff is
 * returned to the scheduler's timed queue rather than sleeping on a worker.
 */
public final class RetryPolicy {
    private static final RetryPolicy NONE = new RetryPolicy(1, Duration.ZERO, Duration.ZERO,
            0.0, Backoff.FIXED, failure -> false);

    private final int maxAttempts;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final double jitter;
    private final Backoff backoff;
    private final Predicate<Throwable> retryPredicate;

    private RetryPolicy(int maxAttempts, Duration initialDelay, Duration maxDelay, double jitter,
            Backoff backoff, Predicate<Throwable> retryPredicate) {
        this.maxAttempts = maxAttempts;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.jitter = jitter;
        this.backoff = backoff;
        this.retryPredicate = retryPredicate;
    }

    public static RetryPolicy none() {
        return NONE;
    }

    public static Builder fixedDelay() {
        return new Builder(Backoff.FIXED);
    }

    public static Builder exponentialBackoff() {
        return new Builder(Backoff.EXPONENTIAL);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration initialDelay() {
        return initialDelay;
    }

    public Duration maxDelay() {
        return maxDelay;
    }

    public double jitter() {
        return jitter;
    }

    boolean shouldRetry(int failedAttempt, Throwable failure) {
        return failedAttempt < maxAttempts && retryPredicate.test(failure);
    }

    Duration delayBeforeAttempt(int nextAttempt, double randomSample) {
        if (nextAttempt < 2) {
            throw new IllegalArgumentException("nextAttempt must be at least 2");
        }
        if (randomSample < 0.0 || randomSample > 1.0) {
            throw new IllegalArgumentException("randomSample must be between 0 and 1");
        }
        long initialNanos = saturatedNanos(initialDelay);
        long maximumNanos = saturatedNanos(maxDelay);
        long base = initialNanos;
        if (backoff == Backoff.EXPONENTIAL) {
            int shift = Math.min(62, nextAttempt - 2);
            if (initialNanos > (Long.MAX_VALUE >> shift)) {
                base = Long.MAX_VALUE;
            } else {
                base = initialNanos << shift;
            }
        }
        base = Math.min(base, maximumNanos);
        double factor = 1.0 + ((randomSample * 2.0) - 1.0) * jitter;
        long jittered = factor >= Long.MAX_VALUE / (double) Math.max(1L, base)
                ? Long.MAX_VALUE : Math.round(base * factor);
        return Duration.ofNanos(Math.min(maximumNanos, Math.max(0L, jittered)));
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private enum Backoff {
        FIXED,
        EXPONENTIAL
    }

    public static final class Builder {
        private final Backoff backoff;
        private int maxAttempts = 3;
        private Duration initialDelay = Duration.ofMillis(100);
        private Duration maxDelay = Duration.ofSeconds(30);
        private double jitter;
        private final List<Class<? extends Throwable>> retryTypes = new ArrayList<>();
        private Predicate<Throwable> customPredicate;

        private Builder(Backoff backoff) {
            this.backoff = backoff;
        }

        public Builder maxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts must be positive");
            }
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder delay(Duration delay) {
            return initialDelay(delay).maxDelay(delay);
        }

        public Builder initialDelay(Duration initialDelay) {
            this.initialDelay = requireNonNegative(initialDelay, "initialDelay");
            return this;
        }

        public Builder maxDelay(Duration maxDelay) {
            this.maxDelay = requireNonNegative(maxDelay, "maxDelay");
            return this;
        }

        /** Sets proportional randomization from {@code 0.0} through {@code 1.0}. */
        public Builder jitter(double jitter) {
            if (jitter < 0.0 || jitter > 1.0 || !Double.isFinite(jitter)) {
                throw new IllegalArgumentException("jitter must be between 0.0 and 1.0");
            }
            this.jitter = jitter;
            return this;
        }

        /** Restricts retries to failures assignable to the supplied type. May be called repeatedly. */
        public Builder retryOn(Class<? extends Throwable> failureType) {
            retryTypes.add(Objects.requireNonNull(failureType, "failureType"));
            return this;
        }

        public Builder retryIf(Predicate<Throwable> predicate) {
            customPredicate = Objects.requireNonNull(predicate, "predicate");
            return this;
        }

        public RetryPolicy build() {
            if (maxDelay.compareTo(initialDelay) < 0) {
                throw new IllegalStateException("maxDelay must not be less than initialDelay");
            }
            Predicate<Throwable> predicate;
            if (!retryTypes.isEmpty()) {
                List<Class<? extends Throwable>> types = List.copyOf(retryTypes);
                predicate = failure -> types.stream().anyMatch(type -> type.isInstance(failure));
                if (customPredicate != null) {
                    predicate = predicate.and(customPredicate);
                }
            } else if (customPredicate != null) {
                predicate = customPredicate;
            } else {
                predicate = failure -> failure instanceof Exception
                        && !(failure instanceof InterruptedException)
                        && !(failure instanceof CancellationException)
                        && !(failure instanceof JobTimeoutException);
            }
            return new RetryPolicy(maxAttempts, initialDelay, maxDelay, jitter, backoff, predicate);
        }

        private static Duration requireNonNegative(Duration duration, String name) {
            Objects.requireNonNull(duration, name);
            if (duration.isNegative()) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return duration;
        }
    }
}
