package io.github.voraes.jscheduler;

import java.util.ArrayList;
import java.util.List;

/** Indicates that workflow dependencies contain a directed cycle. */
public final class WorkflowCycleException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final ArrayList<String> cycle;

    WorkflowCycleException(List<String> cycle) {
        super("Workflow dependency cycle: " + String.join(" -> ", cycle));
        this.cycle = new ArrayList<>(cycle);
    }

    public List<String> cycle() {
        return List.copyOf(cycle);
    }
}
