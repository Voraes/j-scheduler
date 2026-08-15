package io.github.voraes.jscheduler;

import java.time.Duration;

/** Monotonic token bucket. Callers serialize access through the scheduler lock. */
final class TokenBucket {
    private final RateLimit limit;
    private final double tokensPerNano;
    private double tokens;
    private long lastRefillNanos;

    TokenBucket(RateLimit limit, long nowNanos) {
        this.limit = limit;
        long periodNanos = saturatedNanos(limit.period());
        tokensPerNano = limit.permits() / (double) periodNanos;
        tokens = limit.burstCapacity();
        lastRefillNanos = nowNanos;
    }

    RateLimit limit() {
        return limit;
    }

    long acquireOrDelay(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return 0L;
        }
        return Math.max(1L, (long) Math.ceil((1.0 - tokens) / tokensPerNano));
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastRefillNanos;
        if (elapsed > 0) {
            tokens = Math.min(limit.burstCapacity(), tokens + elapsed * tokensPerNano);
            lastRefillNanos = nowNanos;
        }
    }

    private static long saturatedNanos(Duration duration) {
        try {
            return Math.max(1L, duration.toNanos());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
