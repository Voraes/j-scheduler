package io.github.voraes.jscheduler;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Ordered asynchronous boundary between engine locks and user listeners. */
final class ListenerDispatcher implements AutoCloseable {
    private final JobListener lifecycleListener;
    private final List<JobEventListener> eventListeners;
    private final ExecutorService executor;

    ListenerDispatcher(JobListener lifecycleListener, List<JobEventListener> eventListeners) {
        this.lifecycleListener = lifecycleListener;
        this.eventListeners = eventListeners;
        executor = lifecycleListener == null && eventListeners.isEmpty() ? null
                : Executors.newSingleThreadExecutor(runnable -> Thread.ofPlatform().daemon()
                        .name("j-scheduler-listeners").unstarted(runnable));
    }

    void lifecycle(JobId jobId, JobExecution snapshot) {
        if (lifecycleListener != null) {
            dispatch(() -> lifecycleListener.onTransition(jobId, snapshot));
        }
    }

    void event(JobEvent event) {
        if (!eventListeners.isEmpty()) {
            dispatch(() -> {
                for (JobEventListener listener : eventListeners) {
                    try {
                        listener.onEvent(event);
                    } catch (Throwable ignored) {
                        // A broken event listener does not prevent delivery to other listeners.
                    }
                }
            });
        }
    }

    private void dispatch(Runnable callback) {
        if (executor == null) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    callback.run();
                } catch (Throwable ignored) {
                    // Listener failures are isolated from scheduler and dispatcher infrastructure.
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Shutdown has already accepted all events it will deliver.
        }
    }

    @Override
    public void close() {
        if (executor != null) {
            executor.shutdown();
        }
    }
}
