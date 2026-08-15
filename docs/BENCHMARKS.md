# Benchmark methodology

J-Scheduler benchmarks use OpenJDK JMH 1.37 in a separate Gradle module. They are not correctness
tests, do not run as part of `test`, and do not contain committed performance claims.

## Workloads

- `BatchExecutionBenchmark`: end-to-end batches of 100 and 10,000 short tasks, including scheduler
  creation and shutdown, for bounded platform and virtual-thread modes.
- `SubmissionBenchmark`: submission and ready-queue contention while the only worker is occupied,
  comparing equal and mixed priorities at 100 and 10,000 jobs.
- `BlockingIoBenchmark`: 100 simulated blocking jobs with equal concurrency bounds in both execution
  modes.
- `ResilienceWorkflowBenchmark`: a fail-once retry batch with zero backoff and a four-node diamond
  workflow. These measure framework overhead, not remote-system latency.

Every benchmark returns or waits on observable work so the JVM cannot eliminate the operation. JMH
owns warmup, measurement, isolation, and forks; hand-written wall-clock loops are not used.

## Running

The checked-in defaults use three one-second warmup iterations, five one-second measurement
iterations, two fresh JVM forks, one benchmark thread, and average time in milliseconds per complete
batch or workflow operation:

```bash
./gradlew :benchmarks:jmh
```

Results are written to `benchmarks/build/reports/jmh/results.json` and are intentionally ignored by
Git. Narrow or extend a run with JMH arguments:

```bash
./gradlew :benchmarks:jmh \
  -PjmhArgs='SubmissionBenchmark.* -p batchSize=10000 -prof gc'
```

`./gradlew :benchmarks:jmhSmoke` only verifies harness wiring. Its short output is not performance
evidence.

## Recording a reproducible result

Alongside the JMH JSON, record:

- exact Git commit and whether the worktree was clean;
- JDK vendor and full version;
- CPU model, core count, memory, architecture, and operating system;
- JVM arguments printed by JMH;
- JMH version, warmup, measurement iterations, forks, threads, and parameters;
- power mode, container limits, and other material host load;
- complete workload name and any code or configuration changes.

Use a quiet, fixed-power host. Compare candidates on the same machine in alternating order and retain
error bounds. A faster score is not automatically a useful optimization; profile first and preserve
correctness and readability.

## Profiling

JMH's built-in profilers can inspect allocation and sampled stacks:

```bash
./gradlew :benchmarks:jmh -PjmhArgs='BatchExecutionBenchmark.* -prof gc'
./gradlew :benchmarks:jmh -PjmhArgs='SubmissionBenchmark.* -prof stack'
```

The Phase 5 diagnostic pass found expected per-job allocation and lock-mediated coordination, but no
measured bottleneck that justified changing queue algorithms. Generated diagnostic results remain
local because short profiling runs are not stable cross-machine baselines.
