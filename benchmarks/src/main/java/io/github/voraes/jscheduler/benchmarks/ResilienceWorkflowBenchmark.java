package io.github.voraes.jscheduler.benchmarks;

import io.github.voraes.jscheduler.Job;
import io.github.voraes.jscheduler.RetryPolicy;
import io.github.voraes.jscheduler.Scheduler;
import io.github.voraes.jscheduler.Workflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Timeout;
import org.openjdk.jmh.annotations.Warmup;

/** End-to-end retry-pipeline and workflow-orchestration overhead. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@Timeout(time = 2, timeUnit = TimeUnit.MINUTES)
public class ResilienceWorkflowBenchmark {
    @Param({"100"})
    public int batchSize;

    @Benchmark
    public int retryOnceThenSucceed() {
        RetryPolicy retry = RetryPolicy.fixedDelay()
                .maxAttempts(2)
                .delay(Duration.ZERO)
                .build();
        List<CompletableFuture<?>> completions = new ArrayList<>(batchSize);
        try (Scheduler scheduler = Scheduler.builder().platformThreads(16).build()) {
            for (int index = 0; index < batchSize; index++) {
                AtomicBoolean firstAttempt = new AtomicBoolean(true);
                Job job = Job.builder("retry-benchmark")
                        .task(() -> {
                            if (firstAttempt.getAndSet(false)) {
                                throw new IllegalStateException("expected first-attempt failure");
                            }
                        })
                        .retry(retry)
                        .build();
                completions.add(scheduler.execute(job).completion().toCompletableFuture());
            }
            CompletableFuture.allOf(completions.toArray(CompletableFuture[]::new)).join();
        }
        return batchSize;
    }

    @Benchmark
    public int fourNodeDiamondWorkflow() {
        Workflow workflow = Workflow.builder("benchmark-workflow")
                .job("left", () -> { })
                .job("right", () -> { })
                .job("join", () -> { })
                .job("finish", () -> { })
                .dependsOn("join", "left", "right")
                .dependsOn("finish", "join")
                .build();
        try (Scheduler scheduler = Scheduler.builder().virtualThreads()
                .maxConcurrentJobs(16).build()) {
            scheduler.schedule(workflow).completion().toCompletableFuture().join();
        }
        return workflow.nodes().size();
    }
}
