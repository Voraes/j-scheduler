package io.github.voraes.jscheduler.benchmarks;

import io.github.voraes.jscheduler.Job;
import io.github.voraes.jscheduler.Scheduler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Timeout;
import org.openjdk.jmh.annotations.Warmup;

/** Submission and ready-queue contention while the only platform worker is occupied. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@Timeout(time = 2, timeUnit = TimeUnit.MINUTES)
public class SubmissionBenchmark {
    @Param({"100", "10000"})
    public int batchSize;

    private Scheduler scheduler;
    private CountDownLatch releaseWorker;

    @Setup(Level.Invocation)
    public void blockWorker() throws InterruptedException {
        CountDownLatch workerStarted = new CountDownLatch(1);
        releaseWorker = new CountDownLatch(1);
        scheduler = Scheduler.builder().platformThreads(1).build();
        scheduler.execute(Job.builder("occupied-worker").task(() -> {
            workerStarted.countDown();
            await(releaseWorker);
        }).build());
        if (!workerStarted.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Benchmark worker did not start");
        }
    }

    @TearDown(Level.Invocation)
    public void stopScheduler() {
        releaseWorker.countDown();
        scheduler.shutdown();
    }

    @Benchmark
    public int samePriority() {
        submit(false);
        return scheduler.snapshot().trackedJobs();
    }

    @Benchmark
    public int mixedPriorities() {
        submit(true);
        return scheduler.snapshot().trackedJobs();
    }

    private void submit(boolean mixedPriorities) {
        for (int index = 0; index < batchSize; index++) {
            int priority = mixedPriorities ? index & 15 : 0;
            scheduler.execute(Job.builder("submitted-task")
                    .task(() -> { })
                    .priority(priority)
                    .build());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
