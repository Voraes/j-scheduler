package io.github.voraes.jscheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.Test;

class SchedulerStressTest {
    @Test
    void executesTenThousandTasksExactlyOnceFromConcurrentProducers() throws Exception {
        int taskCount = 10_000;
        int producerCount = 32;
        AtomicIntegerArray executions = new AtomicIntegerArray(taskCount);
        CountDownLatch completed = new CountDownLatch(taskCount);

        try (Scheduler scheduler = Scheduler.builder().virtualThreads()
                .maxConcurrentJobs(128).build();
                var producers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> submissions = new ArrayList<>();
            for (int producer = 0; producer < producerCount; producer++) {
                int producerIndex = producer;
                submissions.add(producers.submit(() -> {
                    for (int index = producerIndex; index < taskCount; index += producerCount) {
                        int taskIndex = index;
                        scheduler.execute(Job.builder("stress-task")
                                .task(() -> {
                                    executions.incrementAndGet(taskIndex);
                                    completed.countDown();
                                })
                                .priority(taskIndex & 15)
                                .build());
                    }
                }));
            }
            for (Future<?> submission : submissions) {
                submission.get(30, TimeUnit.SECONDS);
            }
            assertTrue(completed.await(30, TimeUnit.SECONDS));
            assertTrue(scheduler.shutdownGracefully(Duration.ofSeconds(10)));
        }

        for (int index = 0; index < taskCount; index++) {
            assertEquals(1, executions.get(index), "task " + index);
        }
    }

    @Test
    void cancellationAndShutdownRaceCompletesEveryHandleAtMostOnce() throws Exception {
        int taskCount = 2_000;
        AtomicIntegerArray executions = new AtomicIntegerArray(taskCount);
        List<JobHandle> handles = new ArrayList<>(taskCount);
        Scheduler scheduler = Scheduler.builder().virtualThreads().maxConcurrentJobs(64).build();
        for (int index = 0; index < taskCount; index++) {
            int taskIndex = index;
            handles.add(scheduler.schedule(Job.builder("race-task")
                            .task(() -> executions.incrementAndGet(taskIndex))
                            .build(),
                    Schedule.delayed(Duration.ofMillis(2))));
        }

        try (var racers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> cancellation = racers.submit(() -> {
                for (int index = 0; index < handles.size(); index += 2) {
                    handles.get(index).cancel(true);
                }
            });
            Future<?> shutdown = racers.submit(scheduler::shutdown);
            cancellation.get(30, TimeUnit.SECONDS);
            shutdown.get(30, TimeUnit.SECONDS);
        }

        CompletableFuture<?>[] completions = handles.stream()
                .map(JobHandle::completion)
                .map(stage -> stage.toCompletableFuture().orTimeout(30, TimeUnit.SECONDS))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(completions).join();
        assertTrue(scheduler.isShutdown());
        for (int index = 0; index < taskCount; index++) {
            assertTrue(executions.get(index) <= 1, "task executed more than once: " + index);
        }
    }
}
