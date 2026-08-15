package io.github.voraes.jscheduler.spring;

import io.github.voraes.jscheduler.JobEventListener;
import io.github.voraes.jscheduler.Scheduler;
import java.util.List;

final class ManagedScheduler {
    private final Scheduler scheduler;
    private final Thread shutdownHook;

    ManagedScheduler(JSchedulerProperties properties, List<JobEventListener> listeners,
            List<JSchedulerCustomizer> customizers) {
        Scheduler.Builder builder = Scheduler.builder();
        if (properties.getExecution().getMode() == JSchedulerProperties.Mode.VIRTUAL) {
            builder.virtualThreads().maxConcurrentJobs(
                    properties.getExecution().getMaxConcurrentJobs());
        } else {
            builder.platformThreads(properties.getExecution().getPlatformThreads());
        }
        listeners.forEach(builder::eventListener);
        customizers.forEach(customizer -> customizer.customize(builder));
        scheduler = builder.build();
        customizers.forEach(customizer -> customizer.schedulerCreated(scheduler));
        shutdownHook = properties.isShutdownHook() ? scheduler.registerShutdownHook() : null;
    }

    Scheduler scheduler() {
        return scheduler;
    }

    void removeShutdownHook() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // The JVM is already shutting down and owns hook execution now.
            }
        }
    }
}
