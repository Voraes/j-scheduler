package io.github.voraes.jscheduler;

/** Receives structured scheduler events. Listener failures are isolated. */
@FunctionalInterface
public interface JobEventListener {
    void onEvent(JobEvent event);
}
