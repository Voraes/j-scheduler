package io.github.voraes.jscheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SchedulerTest {
    private Scheduler scheduler;

    @AfterEach
    void stopScheduler() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void executesImmediateJobAndPublishesLifecycle() throws Exception {
        List<JobStatus> transitions = new CopyOnWriteArrayList<>();
        CountDownLatch ran = new CountDownLatch(1);
        scheduler = Scheduler.builder()
                .platformThreads(1)
                .listener((id, execution) -> transitions.add(execution.status()))
                .build();

        JobHandle handle = scheduler.execute(job("once", 0, ran::countDown));

        assertTrue(ran.await(2, TimeUnit.SECONDS));
        awaitStatus(handle, JobStatus.SUCCEEDED);
        awaitSize(transitions, 4);
        assertEquals(List.of(JobStatus.SCHEDULED, JobStatus.READY, JobStatus.RUNNING,
                JobStatus.SUCCEEDED), transitions);
        assertTrue(handle.nextExecution().isEmpty());
        assertEquals(JobStatus.SUCCEEDED, handle.latestExecution().orElseThrow().status());
    }

    @Test
    void prioritizesOnlyJobsThatAreReadyAndPreservesFifoForTies() throws Exception {
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();
        scheduler = Scheduler.builder().platformThreads(1).build();
        scheduler.execute(job("blocker", 0, () -> {
            blockerStarted.countDown();
            await(releaseBlocker);
        }));
        assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));

        JobHandle low = scheduler.execute(job("low", 1, () -> order.add("low")));
        JobHandle highFirst = scheduler.execute(job("high-first", 10, () -> order.add("high-first")));
        JobHandle highSecond = scheduler.execute(job("high-second", 10, () -> order.add("high-second")));
        awaitStatus(low, JobStatus.READY);
        awaitStatus(highFirst, JobStatus.READY);
        awaitStatus(highSecond, JobStatus.READY);
        releaseBlocker.countDown();

        awaitSize(order, 3);
        assertEquals(List.of("high-first", "high-second", "low"), order);
    }

    @Test
    void neverRunsFutureHighPriorityJobBeforeDueLowPriorityJob() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch complete = new CountDownLatch(2);
        scheduler = Scheduler.builder().platformThreads(1).build();
        scheduler.schedule(job("future-high", 100, () -> {
            order.add("future-high");
            complete.countDown();
        }), Schedule.delayed(Duration.ofMillis(150)));
        scheduler.execute(job("due-low", -100, () -> {
            order.add("due-low");
            complete.countDown();
        }));

        assertTrue(complete.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("due-low", "future-high"), order);
    }

    @Test
    void cancellingBeforeExecutionIsTerminalAndIdempotent() throws Exception {
        AtomicBoolean ran = new AtomicBoolean();
        scheduler = Scheduler.builder().platformThreads(1).build();
        JobHandle handle = scheduler.schedule(job("cancel", 0, () -> ran.set(true)),
                Schedule.delayed(Duration.ofSeconds(1)));

        assertTrue(handle.cancel());
        assertFalse(handle.cancel());
        assertEquals(JobStatus.CANCELLED, handle.status());
        assertTrue(handle.nextExecution().isEmpty());
        assertFalse(ran.get());
    }

    @Test
    void interruptionCanBeRequestedForRunningJob() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        scheduler = Scheduler.builder().platformThreads(1).build();
        JobHandle handle = scheduler.execute(job("interrupt", 0, () -> {
            started.countDown();
            try {
                Thread.sleep(Duration.ofMinutes(1));
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(started.await(2, TimeUnit.SECONDS));

        assertTrue(handle.cancel(true));
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertEquals(JobStatus.CANCELLED, handle.status());
    }

    @Test
    void fixedRateCanOverlapAndUsesPlannedCadence() throws Exception {
        CountDownLatch twoStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        scheduler = Scheduler.builder().platformThreads(2).build();
        JobHandle handle = scheduler.schedule(job("rate", 0, () -> {
            int current = concurrent.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            twoStarted.countDown();
            await(release);
            concurrent.decrementAndGet();
        }), Schedule.fixedRate(Duration.ZERO, Duration.ofMillis(30)));

        assertTrue(twoStarted.await(2, TimeUnit.SECONDS));
        handle.cancel(false);
        release.countDown();
        assertEquals(2, maximum.get());
    }

    @Test
    void fixedDelayStartsDelayAfterCompletionAndNeverOverlaps() throws Exception {
        CountDownLatch threeRuns = new CountDownLatch(3);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        List<Long> starts = new CopyOnWriteArrayList<>();
        scheduler = Scheduler.builder().platformThreads(3).build();
        JobHandle handle = scheduler.schedule(job("delay", 0, () -> {
            starts.add(System.nanoTime());
            maximum.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            try {
                Thread.sleep(35);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                concurrent.decrementAndGet();
                threeRuns.countDown();
            }
        }), Schedule.fixedDelay(Duration.ZERO, Duration.ofMillis(35)));

        assertTrue(threeRuns.await(3, TimeUnit.SECONDS));
        handle.cancel(false);
        assertEquals(1, maximum.get());
        assertTrue(Duration.ofNanos(starts.get(1) - starts.get(0)).toMillis() >= 55);
    }

    @Test
    void taskAndListenerFailuresDoNotStopInfrastructure() throws Exception {
        CountDownLatch succeeded = new CountDownLatch(1);
        scheduler = Scheduler.builder().platformThreads(1)
                .listener((id, execution) -> { throw new IllegalStateException("listener"); })
                .build();
        JobHandle failed = scheduler.execute(job("failure", 0,
                () -> { throw new IllegalArgumentException("task"); }));
        scheduler.execute(job("success", 0, succeeded::countDown));

        assertTrue(succeeded.await(2, TimeUnit.SECONDS));
        awaitStatus(failed, JobStatus.FAILED);
        assertInstanceOf(IllegalArgumentException.class,
                failed.latestExecution().orElseThrow().result().orElseThrow()
                        .failure().orElseThrow());
    }

    @Test
    void virtualThreadsRespectConcurrencyLimit() throws Exception {
        int taskCount = 8;
        CountDownLatch firstTwo = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(taskCount);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        List<Boolean> virtual = new CopyOnWriteArrayList<>();
        scheduler = Scheduler.builder().virtualThreads().maxConcurrentJobs(2).build();
        for (int index = 0; index < taskCount; index++) {
            scheduler.execute(job("virtual-" + index, 0, () -> {
                virtual.add(Thread.currentThread().isVirtual());
                maximum.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                firstTwo.countDown();
                await(release);
                concurrent.decrementAndGet();
                completed.countDown();
            }));
        }

        assertTrue(firstTwo.await(2, TimeUnit.SECONDS));
        assertEquals(2, maximum.get());
        release.countDown();
        assertTrue(completed.await(3, TimeUnit.SECONDS));
        assertTrue(virtual.stream().allMatch(Boolean::booleanValue));
        assertEquals(2, maximum.get());
    }

    @Test
    void executesEverySubmittedJobExactlyOnceUnderContention() throws Exception {
        int taskCount = 500;
        CountDownLatch completed = new CountDownLatch(taskCount);
        AtomicIntegerArray invocations = new AtomicIntegerArray(taskCount);
        scheduler = Scheduler.builder().virtualThreads().maxConcurrentJobs(32).build();

        for (int index = 0; index < taskCount; index++) {
            int taskIndex = index;
            scheduler.execute(job("stress-" + index, index % 7, () -> {
                invocations.incrementAndGet(taskIndex);
                completed.countDown();
            }));
        }

        assertTrue(completed.await(5, TimeUnit.SECONDS));
        for (int index = 0; index < taskCount; index++) {
            assertEquals(1, invocations.get(index), "task " + index);
        }
    }

    @Test
    void injectedClockControlsLifecycleTimestamps() throws Exception {
        Instant fixedInstant = Instant.parse("2030-01-02T03:04:05Z");
        scheduler = Scheduler.builder().platformThreads(1)
                .clock(Clock.fixed(fixedInstant, ZoneOffset.UTC))
                .build();
        JobHandle handle = scheduler.execute(job("clock", 0, () -> { }));

        awaitStatus(handle, JobStatus.SUCCEEDED);
        JobResult result = handle.latestExecution().orElseThrow().result().orElseThrow();
        assertEquals(fixedInstant, result.startedAt().orElseThrow());
        assertEquals(fixedInstant, result.completedAt());
    }

    @Test
    void gracefulShutdownTimesOutAndInterruptsUnfinishedWork() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        scheduler = Scheduler.builder().platformThreads(1).build();
        scheduler.execute(job("slow", 0, () -> {
            started.countDown();
            try {
                Thread.sleep(Duration.ofMinutes(1));
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(started.await(2, TimeUnit.SECONDS));

        assertFalse(scheduler.shutdownGracefully(Duration.ofMillis(10)));
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
    }

    @Test
    void gracefulShutdownCompletesReadyWorkButCancelsFutureWork() throws Exception {
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean readyRan = new AtomicBoolean();
        AtomicBoolean futureRan = new AtomicBoolean();
        scheduler = Scheduler.builder().platformThreads(1).build();
        scheduler.execute(job("blocker", 0, () -> {
            blockerStarted.countDown();
            await(release);
        }));
        assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));
        JobHandle ready = scheduler.execute(job("ready", 0, () -> readyRan.set(true)));
        JobHandle future = scheduler.schedule(job("future", 0, () -> futureRan.set(true)),
                Schedule.delayed(Duration.ofMinutes(1)));

        awaitStatus(ready, JobStatus.READY);
        Thread releaser = Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            release.countDown();
        });
        assertTrue(scheduler.shutdownGracefully(Duration.ofSeconds(2)));
        releaser.join();
        assertTrue(readyRan.get());
        assertFalse(futureRan.get());
        assertEquals(JobStatus.CANCELLED, future.status());
        assertTrue(scheduler.isShutdown());
        assertTrue(scheduler.isTerminated());
        assertThrows(RejectedExecutionException.class,
                () -> scheduler.execute(job("rejected", 0, () -> { })));
    }

    @Test
    void immediateShutdownInterruptsAndRemovesQueuedWork() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean queuedRan = new AtomicBoolean();
        scheduler = Scheduler.builder().platformThreads(1).build();
        scheduler.execute(job("running", 0, () -> {
            started.countDown();
            try {
                Thread.sleep(Duration.ofMinutes(1));
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(started.await(2, TimeUnit.SECONDS));
        JobHandle queued = scheduler.execute(job("queued", 0, () -> queuedRan.set(true)));
        awaitStatus(queued, JobStatus.READY);

        scheduler.shutdown();

        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertFalse(queuedRan.get());
        assertEquals(JobStatus.CANCELLED, queued.status());
    }

    @Test
    void validatesArgumentsAndCreatesDistinctIds() {
        scheduler = Scheduler.builder().platformThreads(1).build();
        JobHandle first = scheduler.schedule(job("one", 0, () -> { }),
                Schedule.delayed(Duration.ofHours(1)));
        JobHandle second = scheduler.schedule(job("two", 0, () -> { }),
                Schedule.delayed(Duration.ofHours(1)));

        assertNotEquals(first.id(), second.id());
        assertThrows(IllegalArgumentException.class,
                () -> Schedule.fixedRate(Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> Scheduler.builder().maxConcurrentJobs(0));
        assertThrows(IllegalStateException.class, () -> Job.builder("missing-task").build());
    }

    @Test
    void shutdownReleasesSchedulerInfrastructureThreads() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        scheduler = Scheduler.builder().platformThreads(2)
                .eventListener(event -> { })
                .build();
        scheduler.execute(Job.builder("resources").task(completed::countDown)
                .timeout(Duration.ofSeconds(1)).build());
        assertTrue(completed.await(1, TimeUnit.SECONDS));

        assertTrue(scheduler.shutdownGracefully(Duration.ofSeconds(1)));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (hasSchedulerInfrastructureThread() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertFalse(hasSchedulerInfrastructureThread());
    }

    @Test
    void completionCallbacksCannotBlockTheWorkerThatCompletedTheJob() throws Exception {
        CountDownLatch followUpRan = new CountDownLatch(1);
        scheduler = Scheduler.builder().platformThreads(1).build();
        JobHandle first = scheduler.execute(Job.builder("first").task(() -> { }).build());

        var callback = first.completion().thenRun(() -> {
            scheduler.execute(Job.builder("follow-up").task(followUpRan::countDown).build());
            try {
                if (!followUpRan.await(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("Follow-up job could not use the released worker");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        callback.toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private static Job job(String name, int priority, Runnable task) {
        return Job.builder(name).priority(priority).task(task).build();
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

    private static void awaitStatus(JobHandle handle, JobStatus expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (handle.status() != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, handle.status());
    }

    private static void awaitSize(List<?> values, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (values.size() != expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, values.size());
    }

    private static boolean hasSchedulerInfrastructureThread() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && thread.getName().startsWith("j-scheduler-"));
    }
}
