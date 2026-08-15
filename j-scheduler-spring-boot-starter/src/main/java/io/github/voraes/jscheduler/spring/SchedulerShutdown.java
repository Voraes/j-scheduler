package io.github.voraes.jscheduler.spring;

import java.time.Duration;

final class SchedulerShutdown implements AutoCloseable {
    private final ManagedScheduler managed;
    private final Duration timeout;

    SchedulerShutdown(ManagedScheduler managed, Duration timeout) {
        this.managed = managed;
        this.timeout = timeout;
    }

    @Override
    public void close() {
        managed.removeShutdownHook();
        try {
            if (!managed.scheduler().shutdownGracefully(timeout)) {
                managed.scheduler().shutdown();
            }
        } catch (InterruptedException interrupted) {
            managed.scheduler().shutdown();
            Thread.currentThread().interrupt();
        }
    }
}
