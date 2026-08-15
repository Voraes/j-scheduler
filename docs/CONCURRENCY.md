# Concurrency model

This document records the invariants used during the Phase 5 repository-wide concurrency review.

## Scheduler state

One `ReentrantLock` guards scheduler lifecycle flags, future and ready queues, tracked handles,
occurrence state, active counts, named token buckets, and per-job circuit breakers. Public handle reads
take the same lock, so snapshots do not observe partially applied state transitions.

The coordinator waits on a condition until the earliest monotonic deadline changes or expires.
Workers wait on the same condition for ready work and re-check predicates in loops, preventing lost or
spurious wakeups. Wall-clock `Clock` values are used only for public timestamps; `System.nanoTime()` is
used for elapsed delays, rate limits, and circuit deadlines.

## Execution and priority

Future occurrences and ready occurrences use separate priority queues. Moving an occurrence between
them is atomic under the scheduler lock. Workers prepare an occurrence—incrementing both active
counts and transitioning it to `RUNNING`—before releasing the lock. This prevents two workers from
admitting the same occurrence or violating `QUEUE`/`SKIP_IF_RUNNING` checks.

Priority is deterministic at the ready-queue boundary. Once several workers have taken jobs, operating
system scheduling can naturally change their observed start order.

## Cancellation, timeout, and shutdown

Cancellation state changes and queue removal use the scheduler lock. Running tasks receive at most one
cancellation event even when cancellation and completion race. Timeout callbacks take the same lock,
record a terminal-facing timeout snapshot, and request interruption; the execution permit is released
only when user code actually returns.

Graceful shutdown rejects new work, stops recurrence, removes future work, and waits in a predicate
loop for ready and active work. Timeout escalation cancels ready work and interrupts running work.
Immediate shutdown performs that escalation directly. Infrastructure executors and dispatchers are
stopped exactly once.

Interruption is cooperative. A task that ignores it can delay physical termination; no API claims
otherwise.

## Workflows and listeners

Each workflow run serializes graph mutation on a dedicated virtual-thread executor. External
cancellation and terminal result construction are synchronized, while public status/result fields are
volatile. Nodes always enter the ordinary scheduler and therefore cannot bypass global concurrency or
resilience policies.

Lifecycle events cross a single-threaded asynchronous boundary before invoking user listeners. This
preserves event order, prevents reentrant listeners from running under engine locks, and isolates one
listener's failure from other listeners and scheduler infrastructure.

## Stress coverage

`./gradlew stressTest` exercises 10,000 exactly-once tasks from concurrent producers and races
cancellation against immediate shutdown across 2,000 handles. The bounded profile is separate from
ordinary tests but runs in CI on Java 21. Unit and integration suites additionally cover recurrence,
priority, retries, timeout, all concurrency policies, virtual-thread limits, listener reentrancy,
workflow failure/cancellation, and infrastructure-thread release.

Named rate-limit groups intentionally retain their token bucket for the scheduler lifetime. Releasing
the bucket between sequential handles would reset token history and violate the shared rate limit;
applications should use a bounded set of configuration keys.
