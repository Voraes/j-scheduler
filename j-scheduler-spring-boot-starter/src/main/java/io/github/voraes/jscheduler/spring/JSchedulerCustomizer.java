package io.github.voraes.jscheduler.spring;

import io.github.voraes.jscheduler.Scheduler;

/** Extension point for custom listeners or builder settings in auto-configured applications. */
public interface JSchedulerCustomizer {
    /** Invoked before the auto-configured scheduler is built. */
    default void customize(Scheduler.Builder builder) { }

    /** Invoked after construction, before the scheduler bean is exposed. */
    default void schedulerCreated(Scheduler scheduler) { }
}
