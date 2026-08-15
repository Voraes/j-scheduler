package io.github.voraes.jscheduler;

enum ExecutionState {
    SCHEDULED,
    READY,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    SKIPPED;

    boolean canTransitionTo(ExecutionState next) {
        return switch (this) {
            case SCHEDULED -> next == READY || next == CANCELLED;
            case READY -> next == RUNNING || next == CANCELLED || next == SKIPPED;
            case RUNNING -> next == SUCCEEDED || next == FAILED || next == CANCELLED
                    || next == TIMED_OUT;
            case SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, SKIPPED -> false;
        };
    }

    boolean terminal() {
        return switch (this) {
            case SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, SKIPPED -> true;
            default -> false;
        };
    }
}
