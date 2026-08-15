package io.github.voraes.jscheduler;

/** Controls overlap between occurrences of the same recurring job. */
public enum ConcurrencyPolicy {
    /** Occurrences may overlap when execution capacity is available. */
    ALLOW,
    /** An occurrence is skipped when another occurrence is already running. */
    SKIP_IF_RUNNING,
    /** Due occurrences wait and execute one at a time. */
    QUEUE,
    /** Running occurrences receive an interruption request before the new occurrence starts. */
    REPLACE
}
