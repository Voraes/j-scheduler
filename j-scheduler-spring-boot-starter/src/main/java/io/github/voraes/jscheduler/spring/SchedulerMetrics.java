package io.github.voraes.jscheduler.spring;

import io.github.voraes.jscheduler.JobEvent;
import io.github.voraes.jscheduler.Scheduler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicBoolean;

final class SchedulerMetrics implements JSchedulerCustomizer {
    private final MeterRegistry registry;
    private final Counter scheduled;
    private final Counter completed;
    private final Counter failed;
    private final Counter retried;
    private final Counter skipped;
    private final Timer duration;
    private final AtomicBoolean bound = new AtomicBoolean();

    SchedulerMetrics(MeterRegistry registry) {
        this.registry = registry;
        scheduled = registry.counter("j.scheduler.jobs.scheduled");
        completed = registry.counter("j.scheduler.jobs.completed");
        failed = registry.counter("j.scheduler.jobs.failed");
        retried = registry.counter("j.scheduler.jobs.retried");
        skipped = registry.counter("j.scheduler.jobs.skipped");
        duration = registry.timer("j.scheduler.job.duration");
    }

    @Override
    public void customize(Scheduler.Builder builder) {
        builder.eventListener(this::onEvent);
    }

    @Override
    public void schedulerCreated(Scheduler scheduler) {
        if (!bound.compareAndSet(false, true)) {
            return;
        }
        Gauge.builder("j.scheduler.jobs.running", scheduler,
                value -> value.snapshot().runningOccurrences()).strongReference(true)
                .register(registry);
        Gauge.builder("j.scheduler.queue.size", scheduler, value -> {
            var snapshot = value.snapshot();
            return snapshot.scheduledOccurrences() + snapshot.readyOccurrences();
        }).strongReference(true).register(registry);
    }

    private void onEvent(JobEvent event) {
        switch (event) {
            case JobEvent.JobScheduled ignored -> scheduled.increment();
            case JobEvent.JobSucceeded succeeded -> {
                completed.increment();
                duration.record(succeeded.duration());
            }
            case JobEvent.JobFailed failure -> {
                completed.increment();
                failed.increment();
                duration.record(failure.duration());
            }
            case JobEvent.JobRetryScheduled ignored -> retried.increment();
            case JobEvent.JobSkipped ignored -> {
                skipped.increment();
                completed.increment();
            }
            case JobEvent.JobTimedOut ignored -> {
                failed.increment();
                completed.increment();
            }
            case JobEvent.JobCancelled ignored -> completed.increment();
            case JobEvent.JobStarted ignored -> { }
            case JobEvent.JobRateLimited ignored -> { }
            case JobEvent.CircuitOpened ignored -> { }
            case JobEvent.CircuitClosed ignored -> { }
        }
    }
}
