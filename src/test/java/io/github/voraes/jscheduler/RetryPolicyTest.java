package io.github.voraes.jscheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {
    @Test
    void fixedDelayIsConstant() {
        RetryPolicy policy = RetryPolicy.fixedDelay()
                .maxAttempts(4)
                .delay(Duration.ofMillis(250))
                .build();

        assertEquals(Duration.ofMillis(250), policy.delayBeforeAttempt(2, 0.0));
        assertEquals(Duration.ofMillis(250), policy.delayBeforeAttempt(4, 1.0));
    }

    @Test
    void exponentialBackoffCapsAtMaximum() {
        RetryPolicy policy = RetryPolicy.exponentialBackoff()
                .initialDelay(Duration.ofMillis(100))
                .maxDelay(Duration.ofMillis(350))
                .build();

        assertEquals(Duration.ofMillis(100), policy.delayBeforeAttempt(2, 0.5));
        assertEquals(Duration.ofMillis(200), policy.delayBeforeAttempt(3, 0.5));
        assertEquals(Duration.ofMillis(350), policy.delayBeforeAttempt(4, 0.5));
        assertEquals(Duration.ofMillis(350), policy.delayBeforeAttempt(30, 0.5));
    }

    @Test
    void jitterStaysWithinBoundsAndMaximum() {
        RetryPolicy policy = RetryPolicy.fixedDelay()
                .initialDelay(Duration.ofMillis(100))
                .maxDelay(Duration.ofMillis(150))
                .jitter(0.25)
                .build();

        assertEquals(Duration.ofMillis(75), policy.delayBeforeAttempt(2, 0.0));
        assertEquals(Duration.ofMillis(125), policy.delayBeforeAttempt(2, 1.0));
    }

    @Test
    void filteringAndAttemptLimitAreExplicit() {
        RetryPolicy policy = RetryPolicy.fixedDelay()
                .maxAttempts(2)
                .retryOn(IOException.class)
                .build();

        assertTrue(policy.shouldRetry(1, new IOException("transient")));
        assertFalse(policy.shouldRetry(1, new IllegalStateException("permanent")));
        assertFalse(policy.shouldRetry(2, new IOException("exhausted")));
    }

    @Test
    void timeoutIsNotRetriedByDefaultButCanBeSelected() {
        JobTimeoutException timeout = new JobTimeoutException(Duration.ofSeconds(1));
        RetryPolicy defaults = RetryPolicy.fixedDelay().build();
        RetryPolicy selected = RetryPolicy.fixedDelay().retryOn(JobTimeoutException.class).build();

        assertFalse(defaults.shouldRetry(1, timeout));
        assertTrue(selected.shouldRetry(1, timeout));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> RetryPolicy.fixedDelay().maxAttempts(0));
        assertThrows(IllegalArgumentException.class,
                () -> RetryPolicy.fixedDelay().jitter(1.1));
        assertThrows(IllegalStateException.class, () -> RetryPolicy.exponentialBackoff()
                .initialDelay(Duration.ofSeconds(2))
                .maxDelay(Duration.ofSeconds(1))
                .build());
    }
}
