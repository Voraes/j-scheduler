package io.github.voraes.jscheduler;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Thread-safe control and observation of one workflow run. */
public interface WorkflowHandle {
    WorkflowId id();

    String name();

    WorkflowStatus status();

    Optional<WorkflowResult> result();

    CompletionStage<WorkflowResult> completion();

    default boolean cancel() {
        return cancel(false);
    }

    boolean cancel(boolean mayInterruptIfRunning);
}
