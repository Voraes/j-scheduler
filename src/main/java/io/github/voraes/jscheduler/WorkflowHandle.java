package io.github.voraes.jscheduler;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Thread-safe control and observation of one workflow run. */
public interface WorkflowHandle {
    WorkflowId id();

    String name();

    WorkflowStatus status();

    Optional<WorkflowResult> result();

    /** Completes normally with the immutable terminal workflow result. */
    CompletionStage<WorkflowResult> completion();

    default boolean cancel() {
        return cancel(false);
    }

    /**
     * Prevents pending nodes from starting and cancels active node handles. Interruption, when
     * requested, remains cooperative.
     */
    boolean cancel(boolean mayInterruptIfRunning);
}
