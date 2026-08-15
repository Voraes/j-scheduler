package io.github.voraes.jscheduler;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

final class DefaultScheduler implements Scheduler {
    private static final long MAX_DELAY_NANOS = Long.MAX_VALUE / 2;
    private static final Comparator<ScheduledOccurrence> BY_DUE_TIME = (left, right) -> {
        long difference = left.deadlineNanos - right.deadlineNanos;
        return difference == 0 ? Long.compare(left.order, right.order) : difference < 0 ? -1 : 1;
    };
    private static final Comparator<ScheduledOccurrence> BY_READY_PRIORITY = Comparator
            .comparingInt((ScheduledOccurrence occurrence) -> occurrence.owner.job.priority()).reversed()
            .thenComparingLong(occurrence -> occurrence.order);

    private final Clock clock;
    private final ListenerDispatcher listeners;
    private final ExecutionMode mode;
    private final int concurrency;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final Condition terminated = lock.newCondition();
    private final PriorityQueue<ScheduledOccurrence> scheduled = new PriorityQueue<>(BY_DUE_TIME);
    private final PriorityQueue<ScheduledOccurrence> ready = new PriorityQueue<>(BY_READY_PRIORITY);
    private final Set<ScheduledJob> handles = new HashSet<>();
    private final Map<String, TokenBucket> namedBuckets = new HashMap<>();
    private final AtomicLong order = new AtomicLong();
    private final Thread coordinator;
    private final ArrayList<Thread> executors = new ArrayList<>();
    private final ScheduledThreadPoolExecutor timeoutExecutor;
    private boolean accepting = true;
    private boolean stopping;
    private boolean infrastructureStopped;
    private int activeExecutions;

    DefaultScheduler(ExecutionMode mode, int concurrency, Clock clock, JobListener lifecycleListener,
            List<JobEventListener> eventListeners) {
        this.mode = mode;
        this.concurrency = concurrency;
        this.clock = clock;
        listeners = new ListenerDispatcher(lifecycleListener, eventListeners);
        timeoutExecutor = new ScheduledThreadPoolExecutor(1, runnable ->
                Thread.ofPlatform().daemon().name("j-scheduler-timeouts").unstarted(runnable));
        timeoutExecutor.setRemoveOnCancelPolicy(true);
        timeoutExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        coordinator = Thread.ofPlatform().name("j-scheduler-coordinator").unstarted(this::coordinate);
        coordinator.start();
        startExecutors();
    }

    private void startExecutors() {
        if (mode == ExecutionMode.PLATFORM) {
            for (int index = 0; index < concurrency; index++) {
                Thread worker = Thread.ofPlatform().name("j-scheduler-worker-" + index)
                        .unstarted(this::platformWorker);
                executors.add(worker);
                worker.start();
            }
        } else {
            Thread dispatcher = Thread.ofPlatform().name("j-scheduler-virtual-dispatcher")
                    .unstarted(this::virtualDispatcher);
            executors.add(dispatcher);
            dispatcher.start();
        }
    }

    @Override
    public JobHandle schedule(Job job, Schedule schedule) {
        java.util.Objects.requireNonNull(job, "job");
        java.util.Objects.requireNonNull(schedule, "schedule");
        lock.lock();
        try {
            if (!accepting) {
                throw new RejectedExecutionException("Scheduler is shutting down");
            }
            TokenBucket bucket = createBucket(job);
            ScheduledJob handle = new ScheduledJob(JobId.random(), job, schedule, bucket,
                    job.circuitBreakerPolicy().map(CircuitBreaker::new).orElse(null), lock,
                    this::cancel);
            handles.add(handle);
            enqueueNewOccurrence(handle, schedule.initialDelay(), clock.instant());
            return handle;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public WorkflowHandle schedule(Workflow workflow) {
        java.util.Objects.requireNonNull(workflow, "workflow");
        lock.lock();
        try {
            if (!accepting) {
                throw new RejectedExecutionException("Scheduler is shutting down");
            }
            return new WorkflowRun(this, workflow, clock).start();
        } finally {
            lock.unlock();
        }
    }

    private TokenBucket createBucket(Job job) {
        Optional<RateLimit> configured = job.rateLimit();
        if (configured.isEmpty()) {
            return null;
        }
        RateLimit limit = configured.orElseThrow();
        Optional<String> group = job.rateLimitGroup();
        if (group.isEmpty()) {
            return new TokenBucket(limit, System.nanoTime());
        }
        TokenBucket existing = namedBuckets.get(group.orElseThrow());
        if (existing != null && !existing.limit().equals(limit)) {
            throw new IllegalArgumentException("Rate-limit group '" + group.orElseThrow()
                    + "' already uses a different configuration");
        }
        return namedBuckets.computeIfAbsent(group.orElseThrow(),
                ignored -> new TokenBucket(limit, System.nanoTime()));
    }

    private void enqueueNewOccurrence(ScheduledJob handle, Duration delay, Instant baseInstant) {
        Instant scheduledFor = safePlus(baseInstant, delay);
        enqueue(handle, handle.nextSequence++, 1, delay, scheduledFor);
    }

    private void enqueue(ScheduledJob handle, long sequence, int attempt, Duration delay,
            Instant scheduledFor) {
        long delayNanos = toNanosSaturated(delay);
        Instant eligibleAt = safePlus(clock.instant(), delay);
        ScheduledOccurrence occurrence = new ScheduledOccurrence(handle, sequence, attempt, order.incrementAndGet(),
                System.nanoTime() + delayNanos, scheduledFor, eligibleAt);
        handle.occurrences.add(occurrence);
        scheduled.add(occurrence);
        refreshNextExecution(handle);
        changed.signalAll();
        publishLifecycle(handle, occurrence);
        publishEvent(new JobEvent.JobScheduled(context(occurrence), eligibleAt));
    }

    private void enqueueFixedRate(ScheduledOccurrence previous, Schedule.FixedRate fixedRate) {
        long periodNanos = toNanosSaturated(fixedRate.period());
        Instant planned = safePlus(previous.scheduledFor, fixedRate.period());
        ScheduledOccurrence occurrence = new ScheduledOccurrence(previous.owner, previous.owner.nextSequence++, 1,
                order.incrementAndGet(), previous.deadlineNanos + periodNanos, planned, planned);
        previous.owner.occurrences.add(occurrence);
        scheduled.add(occurrence);
        refreshNextExecution(previous.owner);
        publishLifecycle(previous.owner, occurrence);
        publishEvent(new JobEvent.JobScheduled(context(occurrence), planned));
    }

    private void enqueueRetry(ScheduledOccurrence failed, Throwable failure) {
        int nextAttempt = failed.attempt + 1;
        Duration delay = failed.owner.job.retryPolicy().delayBeforeAttempt(nextAttempt,
                ThreadLocalRandom.current().nextDouble());
        enqueue(failed.owner, failed.sequence, nextAttempt, delay, failed.scheduledFor);
        publishEvent(new JobEvent.JobRetryScheduled(context(failed), nextAttempt, delay, failure));
    }

    private void coordinate() {
        lock.lock();
        try {
            while (!stopping) {
                ScheduledOccurrence first = scheduled.peek();
                if (first == null) {
                    changed.await();
                    continue;
                }
                long waitNanos = first.deadlineNanos - System.nanoTime();
                if (waitNanos > 0) {
                    changed.awaitNanos(waitNanos);
                    continue;
                }
                moveDueOccurrences();
                changed.signalAll();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();
        }
    }

    private void moveDueOccurrences() {
        long now = System.nanoTime();
        ScheduledOccurrence occurrence;
        while ((occurrence = scheduled.peek()) != null && occurrence.deadlineNanos - now <= 0) {
            scheduled.remove();
            long rateDelay = occurrence.owner.bucket == null ? 0L
                    : occurrence.owner.bucket.acquireOrDelay(now);
            if (rateDelay > 0) {
                deferForRateLimit(occurrence, rateDelay);
                continue;
            }
            transition(occurrence, ExecutionState.READY, JobStatus.READY, null);
            ready.add(occurrence);
            if (occurrence.owner.schedule instanceof Schedule.FixedRate fixedRate
                    && occurrence.attempt == 1 && !occurrence.owner.recurrenceStopped) {
                enqueueFixedRate(occurrence, fixedRate);
            }
            refreshNextExecution(occurrence.owner);
        }
    }

    private void deferForRateLimit(ScheduledOccurrence occurrence, long delayNanos) {
        occurrence.deadlineNanos = System.nanoTime() + delayNanos;
        Duration delay = Duration.ofNanos(delayNanos);
        occurrence.eligibleAt = safePlus(clock.instant(), delay);
        scheduled.add(occurrence);
        refreshNextExecution(occurrence.owner);
        publishEvent(new JobEvent.JobRateLimited(context(occurrence), delay,
                occurrence.owner.job.rateLimitGroup()));
    }

    private void platformWorker() {
        while (true) {
            ScheduledOccurrence occurrence = takeReady(Thread.currentThread());
            if (occurrence == null) {
                return;
            }
            executePrepared(occurrence);
            // Interruption belongs to the completed user task, not to this reusable worker.
            Thread.interrupted();
        }
    }

    private void virtualDispatcher() {
        while (true) {
            ScheduledOccurrence occurrence;
            lock.lock();
            try {
                occurrence = awaitReady(null);
                if (occurrence == null) {
                    return;
                }
                Thread thread = Thread.ofVirtual().name("j-scheduler-job-"
                        + occurrence.owner.id.value()).unstarted(() -> executePrepared(occurrence));
                occurrence.executingThread = thread;
                scheduleTimeout(occurrence);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }
            occurrence.executingThread.start();
        }
    }

    private ScheduledOccurrence takeReady(Thread executingThread) {
        lock.lock();
        try {
            return awaitReady(executingThread);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            lock.unlock();
        }
    }

    private ScheduledOccurrence awaitReady(Thread executingThread) throws InterruptedException {
        while (!stopping) {
            if (mode == ExecutionMode.VIRTUAL && activeExecutions >= concurrency) {
                changed.await();
                continue;
            }
            ScheduledOccurrence occurrence = pollExecutableReady();
            if (occurrence != null) {
                prepareExecution(occurrence, executingThread);
                if (executingThread != null) {
                    scheduleTimeout(occurrence);
                }
                return occurrence;
            }
            changed.await();
        }
        return null;
    }

    private ScheduledOccurrence pollExecutableReady() {
        while (!ready.isEmpty()) {
            ScheduledOccurrence candidate = ready.stream()
                    .filter(occurrence -> occurrence.owner.job.concurrencyPolicy()
                            != ConcurrencyPolicy.QUEUE || occurrence.owner.activeExecutions == 0)
                    .min(BY_READY_PRIORITY)
                    .orElse(null);
            if (candidate == null) {
                return null;
            }
            ready.remove(candidate);
            ConcurrencyPolicy policy = candidate.owner.job.concurrencyPolicy();
            if (policy == ConcurrencyPolicy.SKIP_IF_RUNNING
                    && candidate.owner.activeExecutions > 0) {
                skip(candidate, "another occurrence is running");
                continue;
            }
            if (candidate.owner.breaker != null) {
                CircuitBreaker.Decision decision = candidate.owner.breaker.tryAcquire(System.nanoTime());
                if (!decision.allowed()) {
                    skip(candidate, "circuit breaker is " + candidate.owner.breaker.state());
                    continue;
                }
            }
            if (policy == ConcurrencyPolicy.REPLACE) {
                requestReplacement(candidate.owner);
            }
            return candidate;
        }
        return null;
    }

    private void requestReplacement(ScheduledJob handle) {
        for (ScheduledOccurrence active : handle.occurrences) {
            if (active.state == ExecutionState.RUNNING && !active.timedOut) {
                requestCancellation(active, true);
            }
        }
    }

    private void skip(ScheduledOccurrence occurrence, String reason) {
        Instant now = clock.instant();
        JobResult result = new JobResult(JobStatus.SKIPPED, Optional.empty(), now, Optional.empty());
        transition(occurrence, ExecutionState.SKIPPED, JobStatus.SKIPPED, result);
        publishEvent(new JobEvent.JobSkipped(context(occurrence), reason));
        finishOccurrence(occurrence, now, true);
    }

    private void prepareExecution(ScheduledOccurrence occurrence, Thread executingThread) {
        activeExecutions++;
        occurrence.owner.activeExecutions++;
        occurrence.executingThread = executingThread;
        transition(occurrence, ExecutionState.RUNNING, JobStatus.RUNNING, null);
        occurrence.startedAt = clock.instant();
        publishEvent(new JobEvent.JobStarted(context(occurrence)));
    }

    private void scheduleTimeout(ScheduledOccurrence occurrence) {
        occurrence.owner.job.timeout().ifPresent(timeout -> occurrence.timeoutFuture = timeoutExecutor
                .schedule(() -> timeOut(occurrence, timeout), toNanosSaturated(timeout),
                        TimeUnit.NANOSECONDS));
    }

    private void timeOut(ScheduledOccurrence occurrence, Duration timeout) {
        lock.lock();
        try {
            if (occurrence.state != ExecutionState.RUNNING || occurrence.cancelRequested) {
                return;
            }
            occurrence.timedOut = true;
            Instant timedOutAt = clock.instant();
            occurrence.timeoutResult = new JobResult(JobStatus.TIMED_OUT,
                    Optional.of(occurrence.startedAt), timedOutAt,
                    Optional.of(new JobTimeoutException(timeout)));
            occurrence.snapshot = new JobExecution(occurrence.sequence, occurrence.attempt,
                    occurrence.scheduledFor, JobStatus.TIMED_OUT,
                    Optional.of(occurrence.timeoutResult));
            occurrence.owner.latestExecution = occurrence.snapshot;
            publishLifecycle(occurrence.owner, occurrence);
            Thread thread = occurrence.executingThread;
            if (thread != null) {
                thread.interrupt();
            }
            publishEvent(new JobEvent.JobTimedOut(context(occurrence), timeout));
        } finally {
            lock.unlock();
        }
    }

    private void executePrepared(ScheduledOccurrence occurrence) {
        Throwable failure = null;
        try {
            occurrence.owner.job.task().run();
        } catch (Throwable thrown) {
            failure = thrown;
        }
        completeExecution(occurrence, failure, clock.instant());
    }

    private void completeExecution(ScheduledOccurrence occurrence, Throwable taskFailure, Instant completed) {
        lock.lock();
        try {
            cancelTimeout(occurrence);
            activeExecutions--;
            occurrence.owner.activeExecutions--;
            Throwable failure = taskFailure;
            JobStatus status;
            ExecutionState state;
            if (occurrence.timedOut) {
                status = JobStatus.TIMED_OUT;
                state = ExecutionState.TIMED_OUT;
                failure = occurrence.timeoutResult.failure().orElseThrow();
            } else if (occurrence.cancelRequested || occurrence.owner.cancelled) {
                status = JobStatus.CANCELLED;
                state = ExecutionState.CANCELLED;
                failure = null;
            } else if (failure != null) {
                status = JobStatus.FAILED;
                state = ExecutionState.FAILED;
            } else {
                status = JobStatus.SUCCEEDED;
                state = ExecutionState.SUCCEEDED;
            }
            JobResult result;
            if (occurrence.timedOut) {
                result = occurrence.timeoutResult;
                if (!occurrence.state.canTransitionTo(state)) {
                    throw new IllegalStateException("Invalid timeout completion state");
                }
                occurrence.state = state;
            } else {
                result = new JobResult(status, Optional.of(occurrence.startedAt), completed,
                        Optional.ofNullable(failure));
                transition(occurrence, state, status, result);
            }
            occurrence.executingThread = null;
            publishCompletionEvent(occurrence, result);
            recordCircuitOutcome(occurrence, status);

            boolean retrying = failure != null && accepting && !occurrence.owner.cancelled
                    && occurrence.owner.job.retryPolicy().shouldRetry(occurrence.attempt, failure);
            if (retrying) {
                enqueueRetry(occurrence, failure);
            }
            finishOccurrence(occurrence, completed, !retrying);
            changed.signalAll();
            signalTerminationIfDone();
        } finally {
            lock.unlock();
        }
    }

    private void publishCompletionEvent(ScheduledOccurrence occurrence, JobResult result) {
        Duration duration = durationBetween(result.startedAt().orElse(result.completedAt()),
                result.completedAt());
        switch (result.status()) {
            case SUCCEEDED -> publishEvent(new JobEvent.JobSucceeded(context(occurrence), duration));
            case FAILED -> publishEvent(new JobEvent.JobFailed(context(occurrence), duration,
                    result.failure().orElseThrow()));
            case CANCELLED -> {
                if (!occurrence.cancellationEventPublished) {
                    publishEvent(new JobEvent.JobCancelled(context(occurrence),
                            occurrence.cancelRequested));
                    occurrence.cancellationEventPublished = true;
                }
            }
            case TIMED_OUT, SKIPPED -> { }
            default -> throw new IllegalStateException("Unexpected terminal state " + result.status());
        }
    }

    private void recordCircuitOutcome(ScheduledOccurrence occurrence, JobStatus status) {
        CircuitBreaker breaker = occurrence.owner.breaker;
        if (breaker == null) {
            return;
        }
        CircuitBreaker.Outcome outcome;
        if (status == JobStatus.SUCCEEDED) {
            outcome = breaker.recordSuccess();
        } else if (status == JobStatus.FAILED || status == JobStatus.TIMED_OUT) {
            outcome = breaker.recordFailure(System.nanoTime());
        } else {
            return;
        }
        if (outcome.opened()) {
            publishEvent(new JobEvent.CircuitOpened(context(occurrence),
                    occurrence.owner.job.circuitBreakerPolicy().orElseThrow().openDuration()));
        } else if (outcome.closed()) {
            publishEvent(new JobEvent.CircuitClosed(context(occurrence)));
        }
    }

    private void finishOccurrence(ScheduledOccurrence occurrence, Instant completed, boolean allowRecurrence) {
        if (allowRecurrence && occurrence.owner.schedule instanceof Schedule.FixedDelay fixedDelay
                && occurrence.attempt >= 1 && !occurrence.owner.recurrenceStopped && !stopping) {
            enqueueNewOccurrence(occurrence.owner, fixedDelay.delay(), completed);
        }
        cleanUpIfTerminal(occurrence.owner);
        refreshNextExecution(occurrence.owner);
    }

    private void cancelTimeout(ScheduledOccurrence occurrence) {
        ScheduledFuture<?> timeoutFuture = occurrence.timeoutFuture;
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
            occurrence.timeoutFuture = null;
        }
    }

    private void transition(ScheduledOccurrence occurrence, ExecutionState next, JobStatus publicStatus,
            JobResult result) {
        if (!occurrence.state.canTransitionTo(next)) {
            throw new IllegalStateException("Invalid transition " + occurrence.state + " -> " + next);
        }
        occurrence.state = next;
        occurrence.snapshot = new JobExecution(occurrence.sequence, occurrence.attempt,
                occurrence.scheduledFor, publicStatus, Optional.ofNullable(result));
        occurrence.owner.latestExecution = occurrence.snapshot;
        publishLifecycle(occurrence.owner, occurrence);
    }

    private void publishLifecycle(ScheduledJob handle, ScheduledOccurrence occurrence) {
        listeners.lifecycle(handle.id, occurrence.snapshot);
    }

    private void publishEvent(JobEvent event) {
        listeners.event(event);
    }

    private JobEvent.Context context(ScheduledOccurrence occurrence) {
        return new JobEvent.Context(occurrence.owner.id, occurrence.owner.job.name(),
                occurrence.sequence, occurrence.attempt, clock.instant());
    }

    private void cleanUpIfTerminal(ScheduledJob handle) {
        handle.occurrences.removeIf(occurrence -> occurrence.state.terminal());
        if (handle.occurrences.isEmpty() && handle.activeExecutions == 0) {
            handles.remove(handle);
            handle.complete();
        }
    }

    private void refreshNextExecution(ScheduledJob handle) {
        handle.nextExecution = handle.occurrences.stream()
                .filter(occurrence -> occurrence.state == ExecutionState.SCHEDULED)
                .min(BY_DUE_TIME)
                .map(occurrence -> occurrence.eligibleAt)
                .orElse(null);
    }

    @Override
    public void shutdown() {
        lock.lock();
        try {
            if (infrastructureStopped) {
                return;
            }
            accepting = false;
            stopping = true;
            cancelOccurrences(scheduled, true);
            cancelOccurrences(ready, true);
            interruptRunning();
            stopInfrastructure();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean shutdownGracefully(Duration timeout) throws InterruptedException {
        java.util.Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long remaining = toNanosSaturated(timeout);
        lock.lockInterruptibly();
        try {
            if (infrastructureStopped) {
                return activeExecutions == 0;
            }
            accepting = false;
            for (ScheduledJob handle : handles) {
                handle.recurrenceStopped = true;
            }
            cancelOccurrences(scheduled, false);
            changed.signalAll();
            while ((!ready.isEmpty() || activeExecutions > 0) && remaining > 0) {
                remaining = terminated.awaitNanos(remaining);
            }
            boolean completed = ready.isEmpty() && activeExecutions == 0;
            stopping = true;
            if (!completed) {
                cancelOccurrences(ready, true);
                interruptRunning();
            }
            stopInfrastructure();
            return completed;
        } finally {
            lock.unlock();
        }
    }

    private void cancelOccurrences(PriorityQueue<ScheduledOccurrence> queue, boolean markHandlesCancelled) {
        Set<ScheduledJob> affected = new HashSet<>();
        for (ScheduledOccurrence occurrence : queue) {
            if (occurrence.state == ExecutionState.SCHEDULED || occurrence.state == ExecutionState.READY) {
                cancelOccurrence(occurrence, false);
                if (markHandlesCancelled) {
                    occurrence.owner.cancelled = true;
                }
                affected.add(occurrence.owner);
            }
        }
        queue.clear();
        for (ScheduledJob handle : affected) {
            cleanUpIfTerminal(handle);
            refreshNextExecution(handle);
        }
    }

    private void interruptRunning() {
        for (ScheduledJob handle : handles) {
            handle.cancelled = true;
            handle.recurrenceStopped = true;
            for (ScheduledOccurrence occurrence : handle.occurrences) {
                if (occurrence.state == ExecutionState.RUNNING) {
                    requestCancellation(occurrence, true);
                }
            }
        }
    }

    private void stopInfrastructure() {
        infrastructureStopped = true;
        changed.signalAll();
        terminated.signalAll();
        coordinator.interrupt();
        for (Thread executor : executors) {
            executor.interrupt();
        }
        timeoutExecutor.shutdownNow();
        listeners.close();
    }

    private void signalTerminationIfDone() {
        if (ready.isEmpty() && activeExecutions == 0) {
            terminated.signalAll();
        }
    }

    @Override
    public boolean isShutdown() {
        lock.lock();
        try {
            return !accepting;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isTerminated() {
        lock.lock();
        try {
            return infrastructureStopped && activeExecutions == 0;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public SchedulerSnapshot snapshot() {
        lock.lock();
        try {
            SchedulerStatus status = infrastructureStopped ? SchedulerStatus.TERMINATED
                    : accepting ? SchedulerStatus.RUNNING : SchedulerStatus.SHUTTING_DOWN;
            return new SchedulerSnapshot(status, mode, concurrency, handles.size(), scheduled.size(),
                    ready.size(), activeExecutions);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Thread registerShutdownHook() {
        Thread hook = Thread.ofPlatform().name("j-scheduler-shutdown-hook").unstarted(this::shutdown);
        Runtime.getRuntime().addShutdownHook(hook);
        return hook;
    }

    private boolean cancel(ScheduledJob handle, boolean interrupt) {
        lock.lock();
        try {
            if (handle.cancelled || (handle.occurrences.isEmpty() && handle.activeExecutions == 0)) {
                return false;
            }
            handle.cancelled = true;
            handle.recurrenceStopped = true;
            for (ScheduledOccurrence occurrence : new ArrayList<>(handle.occurrences)) {
                if (occurrence.state == ExecutionState.SCHEDULED) {
                    scheduled.remove(occurrence);
                    cancelOccurrence(occurrence, false);
                } else if (occurrence.state == ExecutionState.READY) {
                    ready.remove(occurrence);
                    cancelOccurrence(occurrence, false);
                } else if (occurrence.state == ExecutionState.RUNNING) {
                    requestCancellation(occurrence, interrupt);
                }
            }
            refreshNextExecution(handle);
            cleanUpIfTerminal(handle);
            changed.signalAll();
            signalTerminationIfDone();
            return true;
        } finally {
            lock.unlock();
        }
    }

    private void cancelOccurrence(ScheduledOccurrence occurrence, boolean interruptionRequested) {
        Instant now = clock.instant();
        transition(occurrence, ExecutionState.CANCELLED, JobStatus.CANCELLED,
                new JobResult(JobStatus.CANCELLED, Optional.empty(), now, Optional.empty()));
        publishEvent(new JobEvent.JobCancelled(context(occurrence), interruptionRequested));
        occurrence.cancellationEventPublished = true;
    }

    private void requestCancellation(ScheduledOccurrence occurrence, boolean interrupt) {
        occurrence.cancelRequested = true;
        if (interrupt && occurrence.executingThread != null) {
            occurrence.executingThread.interrupt();
        }
        if (!occurrence.cancellationEventPublished) {
            publishEvent(new JobEvent.JobCancelled(context(occurrence), interrupt));
            occurrence.cancellationEventPublished = true;
        }
    }

    private static long toNanosSaturated(Duration duration) {
        try {
            return Math.min(duration.toNanos(), MAX_DELAY_NANOS);
        } catch (ArithmeticException overflow) {
            return MAX_DELAY_NANOS;
        }
    }

    private static Instant safePlus(Instant instant, Duration duration) {
        try {
            return instant.plus(duration);
        } catch (DateTimeException | ArithmeticException overflow) {
            return Instant.MAX;
        }
    }

    private static Duration durationBetween(Instant start, Instant end) {
        Duration duration = Duration.between(start, end);
        return duration.isNegative() ? Duration.ZERO : duration;
    }

}
