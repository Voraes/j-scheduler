package io.github.voraes.jscheduler;

/** Controls how a node failure affects the remaining workflow graph. */
public enum WorkflowFailurePolicy {
    /** Cancel active nodes and skip all pending nodes after the first failure. */
    FAIL_WORKFLOW,
    /** Skip transitive dependents of failed nodes while allowing independent branches to finish. */
    SKIP_DEPENDENTS
}
