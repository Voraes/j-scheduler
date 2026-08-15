# Migrating from J-Scheduler 1.x to 2.x

Version 2 is a major API and semantics revision. The default-package `BackgroundTaskScheduler` remains
temporarily as a deprecated compatibility facade, but new code should migrate to the packaged API.

## API mapping

| 1.x concept | 2.x replacement |
| --- | --- |
| `BackgroundTaskScheduler` | `Scheduler` |
| mutable scheduled task | immutable `Job` plus `Schedule` |
| primitive delays and `TimeUnit` | `Duration` |
| implicit task identity | returned `JobHandle` and `JobId` |
| printed failures | `JobResult`, `JobExecution`, and lifecycle events |
| unbounded execution assumptions | explicit platform or virtual-thread concurrency |

## Scheduling

```java
JobHandle handle = scheduler.schedule(
        Job.builder("refresh-cache").task(this::refreshCache).priority(10).build(),
        Schedule.fixedDelay(Duration.ofMinutes(1)));
```

Scheduling now returns a thread-safe handle for status, snapshots, completion, circuit state, and
cancellation. Configuration objects are immutable and reject invalid values when built.

## Corrected priority semantics

Version 1 could let a future high-priority task run early because timer callbacks polled a global
priority queue. Version 2 separates the future delay queue from the ready priority queue:

1. Time determines when an occurrence becomes eligible.
2. Priority orders occurrences that are already eligible.
3. Equal priorities use deterministic FIFO submission order at the queue boundary.

This is an intentional behavior correction. Priority does not alter deadlines.

## Recurrence

Fixed rate follows planned cadence and can overlap. Fixed delay starts after the preceding occurrence,
including its retries, finishes. Choose an explicit `ConcurrencyPolicy` when fixed-rate overlap needs
to be allowed, skipped, queued, or replaced.

## Cancellation, timeout, and shutdown

- `cancel(false)` prevents future work but does not interrupt a running task.
- `cancel(true)`, timeout, `REPLACE`, and immediate shutdown request interruption cooperatively.
- Java cannot safely force arbitrary user code to terminate; code that ignores interruption may keep
  running and retains its concurrency permit.
- `shutdown()` is immediate. `shutdownGracefully(Duration)` drains ready/running work until its bound.

## Virtual threads

Virtual threads are opt-in and always have an explicit `maxConcurrentJobs` bound. This protects finite
downstream systems even when creating threads is cheap.

## Workflows and Spring Boot

Dependency workflows and the Spring Boot starter are new 2.x modules. Workflows submit ordinary jobs
through the same resilience and concurrency pipeline. Spring remains outside the core artifact.
