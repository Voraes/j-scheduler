package io.github.voraes.jscheduler;

/** Per-job circuit-breaker state guarded by the scheduler lock. */
final class CircuitBreaker {
    private final CircuitBreakerPolicy policy;
    private CircuitState state = CircuitState.CLOSED;
    private int consecutiveFailures;
    private int halfOpenPermits;
    private int halfOpenSuccesses;
    private long openedUntilNanos;

    CircuitBreaker(CircuitBreakerPolicy policy) {
        this.policy = policy;
    }

    Decision tryAcquire(long nowNanos) {
        boolean enteredHalfOpen = false;
        if (state == CircuitState.OPEN && openedUntilNanos - nowNanos <= 0) {
            state = CircuitState.HALF_OPEN;
            halfOpenPermits = 0;
            halfOpenSuccesses = 0;
            enteredHalfOpen = true;
        }
        if (state == CircuitState.OPEN) {
            return new Decision(false, false);
        }
        if (state == CircuitState.HALF_OPEN) {
            if (halfOpenPermits >= policy.halfOpenAttempts()) {
                return new Decision(false, enteredHalfOpen);
            }
            halfOpenPermits++;
        }
        return new Decision(true, enteredHalfOpen);
    }

    Outcome recordSuccess() {
        if (state == CircuitState.HALF_OPEN) {
            halfOpenSuccesses++;
            if (halfOpenSuccesses >= policy.halfOpenAttempts()) {
                state = CircuitState.CLOSED;
                consecutiveFailures = 0;
                return new Outcome(false, true);
            }
        } else if (state == CircuitState.CLOSED) {
            consecutiveFailures = 0;
        }
        return Outcome.NONE;
    }

    Outcome recordFailure(long nowNanos) {
        if (state == CircuitState.HALF_OPEN) {
            open(nowNanos);
            return new Outcome(true, false);
        }
        if (state == CircuitState.CLOSED && ++consecutiveFailures >= policy.failureThreshold()) {
            open(nowNanos);
            return new Outcome(true, false);
        }
        return Outcome.NONE;
    }

    CircuitState state() {
        return state;
    }

    private void open(long nowNanos) {
        state = CircuitState.OPEN;
        openedUntilNanos = nowNanos + saturatedNanos(policy.openDuration());
        halfOpenPermits = 0;
        halfOpenSuccesses = 0;
    }

    private static long saturatedNanos(java.time.Duration duration) {
        try {
            return Math.min(Long.MAX_VALUE / 2, duration.toNanos());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE / 2;
        }
    }

    record Decision(boolean allowed, boolean enteredHalfOpen) { }

    record Outcome(boolean opened, boolean closed) {
        private static final Outcome NONE = new Outcome(false, false);
    }
}
