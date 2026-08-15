package io.github.voraes.jscheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ResilientSchedulerTest {
    private Scheduler scheduler;

    @AfterEach
    void stopScheduler() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void retriesUntilSuccessWithoutBlockingWorker() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch unrelatedRan = new CountDownLatch(1);
        List<JobEvent> events = new CopyOnWriteArrayList<>();
        scheduler = Scheduler.builder().platformThreads(1).eventListener(events::add).build();
        Job job = Job.builder("retry")
                .task(() -> {
                    if (attempts.incrementAndGet() < 3) {
                        throw new IllegalStateException("transient");
                    }
                })
                .retry(RetryPolicy.fixedDelay().maxAttempts(3)
                        .delay(Duration.ofMillis(30)).build())
                .build();

        JobHandle handle = scheduler.execute(job);
        scheduler.execute(Job.builder("unrelated").task(unrelatedRan::countDown).build());

        assertTrue(unrelatedRan.await(1, TimeUnit.SECONDS));
        awaitStatus(handle, JobStatus.SUCCEEDED);
        awaitEventCount(events, JobEvent.JobRetryScheduled.class, 2);
        assertEquals(3, attempts.get());
        assertEquals(2, events.stream().filter(JobEvent.JobRetryScheduled.class::isInstance).count());
        assertEquals(3, handle.latestExecution().orElseThrow().attempt());
    }

    @Test
    void retryFilteringAndMaximumAttemptsAreEnforced() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        Job job = Job.builder("filtered")
                .task(() -> {
                    attempts.incrementAndGet();
                    throw new IllegalArgumentException("do not retry");
                })
                .retry(RetryPolicy.fixedDelay().maxAttempts(5)
                        .delay(Duration.ZERO).retryOn(IOException.class).build())
                .build();
        scheduler = Scheduler.builder().platformThreads(1).build();

        JobHandle handle = scheduler.execute(job);

        awaitStatus(handle, JobStatus.FAILED);
        assertEquals(1, attempts.get());
    }

    @Test
    void cancellationDuringBackoffPreventsRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch retryScheduled = new CountDownLatch(1);
        scheduler = Scheduler.builder().platformThreads(1).eventListener(event -> {
            if (event instanceof JobEvent.JobRetryScheduled) {
                retryScheduled.countDown();
            }
        }).build();
        JobHandle handle = scheduler.execute(Job.builder("cancel-retry")
                .task(() -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("fail");
                })
                .retry(RetryPolicy.fixedDelay().maxAttempts(3)
                        .delay(Duration.ofSeconds(1)).build())
                .build());

        assertTrue(retryScheduled.await(1, TimeUnit.SECONDS));
        assertTrue(handle.cancel());
        Thread.sleep(100);
        assertEquals(1, attempts.get());
        assertEquals(JobStatus.CANCELLED, handle.status());
    }

    @Test
    void gracefulShutdownCancelsPendingRetry() throws Exception {
        CountDownLatch retryScheduled = new CountDownLatch(1);
        scheduler = Scheduler.builder().platformThreads(1).eventListener(event -> {
            if (event instanceof JobEvent.JobRetryScheduled) {
                retryScheduled.countDown();
            }
        }).build();
        JobHandle handle = scheduler.execute(Job.builder("shutdown-retry")
                .task(() -> { throw new IllegalStateException("fail"); })
                .retry(RetryPolicy.fixedDelay().maxAttempts(3)
                        .delay(Duration.ofMinutes(1)).build())
                .build());
        assertTrue(retryScheduled.await(1, TimeUnit.SECONDS));

        assertTrue(scheduler.shutdownGracefully(Duration.ofSeconds(1)));
        assertEquals(JobStatus.CANCELLED, handle.status());
    }

    @Test
    void timeoutRequestsInterruptionAndIsNotRetriedByDefault() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch timeoutEvent = new CountDownLatch(1);
        scheduler = Scheduler.builder().platformThreads(1).eventListener(event -> {
            if (event instanceof JobEvent.JobTimedOut) {
                timeoutEvent.countDown();
            }
        }).build();
        JobHandle handle = scheduler.execute(Job.builder("timeout")
                .task(() -> {
                    attempts.incrementAndGet();
                    try {
                        Thread.sleep(Duration.ofMinutes(1));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                })
                .timeout(Duration.ofMillis(40))
                .retry(RetryPolicy.fixedDelay().maxAttempts(3).delay(Duration.ZERO).build())
                .build());

        assertTrue(timeoutEvent.await(1, TimeUnit.SECONDS));
        awaitStatus(handle, JobStatus.TIMED_OUT);
        assertEquals(1, attempts.get());
        assertInstanceOf(JobTimeoutException.class, handle.latestExecution().orElseThrow()
                .result().orElseThrow().failure().orElseThrow());
    }

    @Test
    void timeoutCanBeExplicitlyRetried() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        scheduler = Scheduler.builder().platformThreads(1).build();
        JobHandle handle = scheduler.execute(Job.builder("timeout-retry")
                .task(() -> {
                    if (attempts.incrementAndGet() == 1) {
                        try {
                            Thread.sleep(Duration.ofMinutes(1));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                })
                .timeout(Duration.ofMillis(30))
                .retry(RetryPolicy.fixedDelay().maxAttempts(2).delay(Duration.ZERO)
                        .retryOn(JobTimeoutException.class).build())
                .build());

        awaitStatus(handle, JobStatus.SUCCEEDED);
        assertEquals(2, attempts.get());
    }

    @Test
    void nonCooperativeTimedOutTaskRetainsItsConcurrencyPermit() throws Exception {
        AtomicBoolean release = new AtomicBoolean();
        CountDownLatch secondRan = new CountDownLatch(1);
        scheduler = Scheduler.builder().platformThreads(1).build();
        JobHandle handle = scheduler.execute(Job.builder("non-cooperative")
                .task(() -> {
                    while (!release.get()) {
                        Thread.interrupted();
                        Thread.onSpinWait();
                    }
                })
                .timeout(Duration.ofMillis(30))
                .build());
        scheduler.execute(Job.builder("next").task(secondRan::countDown).build());

        awaitStatus(handle, JobStatus.TIMED_OUT);
        assertFalse(secondRan.await(80, TimeUnit.MILLISECONDS));
        release.set(true);
        assertTrue(secondRan.await(1, TimeUnit.SECONDS));
    }

    @Test
    void skipIfRunningDropsOverlappingOccurrences() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch skipped = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        scheduler = Scheduler.builder().platformThreads(2).eventListener(event -> {
            if (event instanceof JobEvent.JobSkipped) {
                skipped.countDown();
            }
        }).build();
        JobHandle handle = scheduler.schedule(Job.builder("skip")
                .task(() -> {
                    runs.incrementAndGet();
                    firstStarted.countDown();
                    await(release);
                })
                .concurrency(ConcurrencyPolicy.SKIP_IF_RUNNING)
                .build(), Schedule.fixedRate(Duration.ZERO, Duration.ofMillis(20)));

        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        assertTrue(skipped.await(1, TimeUnit.SECONDS));
        handle.cancel(false);
        release.countDown();
        assertEquals(1, runs.get());
    }

    @Test
    void queuePolicySerializesRecurringOccurrences() throws Exception {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(3);
        scheduler = Scheduler.builder().platformThreads(3).build();
        JobHandle handle = scheduler.schedule(Job.builder("queue")
                .task(() -> {
                    maximum.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(35);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        concurrent.decrementAndGet();
                        completed.countDown();
                    }
                })
                .concurrency(ConcurrencyPolicy.QUEUE)
                .build(), Schedule.fixedRate(Duration.ZERO, Duration.ofMillis(10)));

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        handle.cancel(false);
        assertEquals(1, maximum.get());
    }

    @Test
    void replaceRequestsInterruptionBeforeStartingNextOccurrence() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        scheduler = Scheduler.builder().platformThreads(2).build();
        JobHandle handle = scheduler.schedule(Job.builder("replace")
                .task(() -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt == 1) {
                        firstStarted.countDown();
                        try {
                            Thread.sleep(Duration.ofMinutes(1));
                        } catch (InterruptedException expected) {
                            interrupted.countDown();
                            Thread.currentThread().interrupt();
                        }
                    } else {
                        secondStarted.countDown();
                    }
                })
                .concurrency(ConcurrencyPolicy.REPLACE)
                .build(), Schedule.fixedRate(Duration.ZERO, Duration.ofMillis(30)));

        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
        handle.cancel(true);
    }

    @Test
    void namedRateLimitIsSharedAndRejectsConflictingConfiguration() throws Exception {
        RateLimit limit = RateLimit.of(1, Duration.ofMillis(100));
        List<Long> starts = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(2);
        scheduler = Scheduler.builder().platformThreads(2).build();
        for (int index = 0; index < 2; index++) {
            scheduler.execute(Job.builder("limited-" + index)
                    .task(() -> {
                        starts.add(System.nanoTime());
                        completed.countDown();
                    })
                    .rateLimit("remote-api", limit)
                    .build());
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        starts.sort(Long::compareTo);
        assertTrue(Duration.ofNanos(starts.get(1) - starts.get(0)).toMillis() >= 70);
        assertThrows(IllegalArgumentException.class, () -> scheduler.execute(Job.builder("conflict")
                .task(() -> { })
                .rateLimit("remote-api", RateLimit.perSecond(2))
                .build()));
    }

    @Test
    void circuitOpensSkipsAndClosesAfterSuccessfulProbe() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch skipped = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        scheduler = Scheduler.builder().platformThreads(1).eventListener(event -> {
            if (event instanceof JobEvent.CircuitOpened) {
                opened.countDown();
            } else if (event instanceof JobEvent.JobSkipped) {
                skipped.countDown();
            } else if (event instanceof JobEvent.CircuitClosed) {
                closed.countDown();
            }
        }).build();
        JobHandle handle = scheduler.schedule(Job.builder("circuit")
                .task(() -> {
                    if (calls.incrementAndGet() <= 2) {
                        throw new IllegalStateException("remote unavailable");
                    }
                })
                .circuitBreaker(CircuitBreakerPolicy.builder()
                        .failureThreshold(2)
                        .openDuration(Duration.ofMillis(60))
                        .halfOpenAttempts(1)
                        .build())
                .build(), Schedule.fixedRate(Duration.ZERO, Duration.ofMillis(15)));

        assertTrue(opened.await(1, TimeUnit.SECONDS));
        assertEquals(CircuitState.OPEN, handle.circuitState().orElseThrow());
        assertTrue(skipped.await(1, TimeUnit.SECONDS));
        assertTrue(closed.await(2, TimeUnit.SECONDS));
        assertEquals(CircuitState.CLOSED, handle.circuitState().orElseThrow());
        handle.cancel(false);
        assertTrue(calls.get() >= 3);
    }

    @Test
    void eventListenersAreIsolatedFromOneAnother() throws Exception {
        CountDownLatch observed = new CountDownLatch(1);
        scheduler = Scheduler.builder().platformThreads(1)
                .eventListener(event -> { throw new AssertionError("broken"); })
                .eventListener(event -> {
                    if (event instanceof JobEvent.JobSucceeded) {
                        observed.countDown();
                    }
                }).build();

        scheduler.execute(Job.builder("events").task(() -> { }).build());

        assertTrue(observed.await(1, TimeUnit.SECONDS));
    }

    @Test
    void eventListenerCanReenterSchedulerWithoutCorruptingCoordination() throws Exception {
        AtomicReference<Scheduler> reference = new AtomicReference<>();
        CountDownLatch followUpRan = new CountDownLatch(1);
        scheduler = Scheduler.builder().platformThreads(1).eventListener(event -> {
            if (event instanceof JobEvent.JobSucceeded succeeded
                    && succeeded.context().jobName().equals("primary")) {
                reference.get().execute(Job.builder("follow-up")
                        .task(followUpRan::countDown).build());
            }
        }).build();
        reference.set(scheduler);

        scheduler.execute(Job.builder("primary").task(() -> { }).build());

        assertTrue(followUpRan.await(1, TimeUnit.SECONDS));
    }

    private static void awaitStatus(JobHandle handle, JobStatus expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (handle.status() != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, handle.status());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out awaiting test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitEventCount(List<JobEvent> events, Class<? extends JobEvent> type,
            long expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (events.stream().filter(type::isInstance).count() < expected
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }
}
