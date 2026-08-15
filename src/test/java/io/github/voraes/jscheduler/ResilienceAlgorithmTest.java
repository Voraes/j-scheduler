package io.github.voraes.jscheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ResilienceAlgorithmTest {
    @Test
    void tokenBucketRefillsDeterministically() {
        RateLimit limit = new RateLimit(2, Duration.ofSeconds(1), 2);
        TokenBucket bucket = new TokenBucket(limit, 0L);

        assertEquals(0L, bucket.acquireOrDelay(0L));
        assertEquals(0L, bucket.acquireOrDelay(0L));
        assertEquals(Duration.ofMillis(500).toNanos(), bucket.acquireOrDelay(0L));
        assertEquals(0L, bucket.acquireOrDelay(Duration.ofMillis(500).toNanos()));
    }

    @Test
    void circuitTransitionsClosedOpenHalfOpenClosed() {
        CircuitBreaker breaker = new CircuitBreaker(CircuitBreakerPolicy.builder()
                .failureThreshold(2)
                .openDuration(Duration.ofSeconds(1))
                .halfOpenAttempts(1)
                .build());

        assertTrue(breaker.tryAcquire(0L).allowed());
        assertFalse(breaker.recordFailure(0L).opened());
        assertTrue(breaker.tryAcquire(0L).allowed());
        assertTrue(breaker.recordFailure(0L).opened());
        assertEquals(CircuitState.OPEN, breaker.state());
        assertFalse(breaker.tryAcquire(Duration.ofMillis(999).toNanos()).allowed());
        assertTrue(breaker.tryAcquire(Duration.ofSeconds(1).toNanos()).allowed());
        assertEquals(CircuitState.HALF_OPEN, breaker.state());
        assertTrue(breaker.recordSuccess().closed());
        assertEquals(CircuitState.CLOSED, breaker.state());
    }

    @Test
    void failedHalfOpenProbeReopensCircuit() {
        CircuitBreaker breaker = new CircuitBreaker(new CircuitBreakerPolicy(1,
                Duration.ofSeconds(1), 1));
        breaker.tryAcquire(0L);
        breaker.recordFailure(0L);
        breaker.tryAcquire(Duration.ofSeconds(1).toNanos());

        assertTrue(breaker.recordFailure(Duration.ofSeconds(1).toNanos()).opened());
        assertEquals(CircuitState.OPEN, breaker.state());
    }
}
