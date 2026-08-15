package io.github.voraes.jscheduler;

import java.util.Objects;
import java.util.UUID;

/** A stable identifier for a scheduled job. */
public record JobId(String value) {
    public JobId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Job id must not be blank");
        }
    }

    static JobId random() {
        return new JobId(UUID.randomUUID().toString());
    }
}
