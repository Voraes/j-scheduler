package io.github.voraes.jscheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable terminal workflow result. */
public final class WorkflowResult {
    private final WorkflowId id;
    private final String name;
    private final WorkflowStatus status;
    private final Instant startedAt;
    private final Instant completedAt;
    private final Map<String, WorkflowNodeResult> executions;

    WorkflowResult(WorkflowId id, String name, WorkflowStatus status, Instant startedAt,
            Instant completedAt, Map<String, WorkflowNodeResult> executions) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.status = Objects.requireNonNull(status, "status");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
        this.executions = Collections.unmodifiableMap(new LinkedHashMap<>(executions));
    }

    public WorkflowId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public WorkflowStatus status() {
        return status;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public Duration duration() {
        Duration duration = Duration.between(startedAt, completedAt);
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    public Map<String, WorkflowNodeResult> executions() {
        return executions;
    }

    public Optional<WorkflowNodeResult> execution(String node) {
        Objects.requireNonNull(node, "node");
        return Optional.ofNullable(executions.get(node));
    }
}
