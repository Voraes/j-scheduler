package io.github.voraes.jscheduler.benchmarks;

import io.github.voraes.jscheduler.Job;
import io.github.voraes.jscheduler.Scheduler;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Timeout;
import org.openjdk.jmh.annotations.Warmup;

/** Simulated blocking I/O with equal explicit concurrency bounds. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@Timeout(time = 2, timeUnit = TimeUnit.MINUTES)
public class BlockingIoBenchmark {
    private static final int JOBS = 100;

    @Benchmark
    public int platformThreads() throws InterruptedException {
        return runBatch(Scheduler.builder().platformThreads(JOBS).build());
    }

    @Benchmark
    public int virtualThreads() throws InterruptedException {
        return runBatch(Scheduler.builder().virtualThreads().maxConcurrentJobs(JOBS).build());
    }

    private static int runBatch(Scheduler scheduler) throws InterruptedException {
        CountDownLatch completed = new CountDownLatch(JOBS);
        Job blockingJob = Job.builder("simulated-io").task(() -> {
            try {
                Thread.sleep(Duration.ofMillis(2));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                completed.countDown();
            }
        }).build();
        try (scheduler) {
            for (int index = 0; index < JOBS; index++) {
                scheduler.execute(blockingJob);
            }
            if (!completed.await(1, TimeUnit.MINUTES)) {
                throw new IllegalStateException("Blocking benchmark batch did not complete");
            }
        }
        return JOBS;
    }
}
