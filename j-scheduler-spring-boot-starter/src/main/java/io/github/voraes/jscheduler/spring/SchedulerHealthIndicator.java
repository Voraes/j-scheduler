package io.github.voraes.jscheduler.spring;

import io.github.voraes.jscheduler.Scheduler;
import io.github.voraes.jscheduler.SchedulerSnapshot;
import io.github.voraes.jscheduler.SchedulerStatus;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

final class SchedulerHealthIndicator implements HealthIndicator {
    private final Scheduler scheduler;

    SchedulerHealthIndicator(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public Health health() {
        SchedulerSnapshot snapshot = scheduler.snapshot();
        Health.Builder builder = snapshot.status() == SchedulerStatus.RUNNING
                ? Health.up() : Health.outOfService();
        return builder.withDetail("status", snapshot.status())
                .withDetail("executionMode", snapshot.executionMode())
                .withDetail("maxConcurrentJobs", snapshot.maxConcurrentJobs())
                .withDetail("trackedJobs", snapshot.trackedJobs())
                .withDetail("scheduledOccurrences", snapshot.scheduledOccurrences())
                .withDetail("readyOccurrences", snapshot.readyOccurrences())
                .withDetail("runningOccurrences", snapshot.runningOccurrences())
                .build();
    }
}
