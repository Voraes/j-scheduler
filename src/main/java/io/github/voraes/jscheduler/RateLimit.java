package io.github.voraes.jscheduler;

import java.time.Duration;
import java.util.Objects;

/** Token-bucket rate limit configuration. */
public record RateLimit(int permits, Duration period, int burstCapacity) {
    public RateLimit {
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be positive");
        }
        Objects.requireNonNull(period, "period");
        if (period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("period must be positive");
        }
        if (burstCapacity < 1) {
            throw new IllegalArgumentException("burstCapacity must be positive");
        }
    }

    public static RateLimit perSecond(int permits) {
        return new RateLimit(permits, Duration.ofSeconds(1), permits);
    }

    public static RateLimit of(int permits, Duration period) {
        return new RateLimit(permits, period, permits);
    }

    public RateLimit withBurstCapacity(int capacity) {
        return new RateLimit(permits, period, capacity);
    }
}
