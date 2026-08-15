package io.github.voraes.jscheduler;

import java.time.Duration;
import java.util.Objects;

/** Describes when and how often a job runs. */
public sealed interface Schedule permits Schedule.Once, Schedule.FixedRate, Schedule.FixedDelay {
    Duration initialDelay();

    static Schedule immediate() {
        return new Once(Duration.ZERO);
    }

    static Schedule delayed(Duration delay) {
        return new Once(requireNonNegative(delay, "delay"));
    }

    static Schedule fixedRate(Duration period) {
        return fixedRate(Duration.ZERO, period);
    }

    static Schedule fixedRate(Duration initialDelay, Duration period) {
        return new FixedRate(requireNonNegative(initialDelay, "initialDelay"),
                requirePositive(period, "period"));
    }

    static Schedule fixedDelay(Duration delay) {
        return fixedDelay(Duration.ZERO, delay);
    }

    static Schedule fixedDelay(Duration initialDelay, Duration delay) {
        return new FixedDelay(requireNonNegative(initialDelay, "initialDelay"),
                requirePositive(delay, "delay"));
    }

    private static Duration requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }

    private static Duration requirePositive(Duration duration, String name) {
        requireNonNegative(duration, name);
        if (duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    record Once(Duration initialDelay) implements Schedule {
        public Once {
            requireNonNegative(initialDelay, "initialDelay");
        }
    }

    /** Fixed rate uses planned cadence, even when prior executions are still running. */
    record FixedRate(Duration initialDelay, Duration period) implements Schedule {
        public FixedRate {
            requireNonNegative(initialDelay, "initialDelay");
            requirePositive(period, "period");
        }
    }

    /** Fixed delay schedules the next occurrence after the preceding execution completes. */
    record FixedDelay(Duration initialDelay, Duration delay) implements Schedule {
        public FixedDelay {
            requireNonNegative(initialDelay, "initialDelay");
            requirePositive(delay, "delay");
        }
    }
}
