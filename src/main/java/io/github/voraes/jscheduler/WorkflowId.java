package io.github.voraes.jscheduler;

import java.util.Objects;
import java.util.UUID;

/** Stable identifier for one workflow run. */
public record WorkflowId(String value) {
    public WorkflowId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Workflow id must not be blank");
        }
    }

    static WorkflowId random() {
        return new WorkflowId(UUID.randomUUID().toString());
    }
}
