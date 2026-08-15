package io.github.voraes.jscheduler;

import java.time.Duration;

/** Indicates that a job exceeded its configured cooperative timeout. */
public final class JobTimeoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public JobTimeoutException(Duration timeout) {
        super("Job exceeded timeout of " + timeout);
    }
}
