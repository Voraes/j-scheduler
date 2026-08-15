package io.github.voraes.jscheduler;

/** Observable states of a circuit breaker. */
public enum CircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
