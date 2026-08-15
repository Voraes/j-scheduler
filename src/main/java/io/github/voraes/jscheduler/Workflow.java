package io.github.voraes.jscheduler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, thread-safe, validated directed acyclic graph of jobs. Each scheduled run owns its
 * mutable orchestration state and submits nodes through the ordinary scheduler pipeline.
 */
public final class Workflow {
    private final String name;
    private final Map<String, Job> jobs;
    private final Map<String, Set<String>> dependencies;
    private final WorkflowFailurePolicy failurePolicy;

    private Workflow(Builder builder) {
        name = builder.name;
        jobs = Collections.unmodifiableMap(new LinkedHashMap<>(builder.jobs));
        Map<String, Set<String>> copiedDependencies = new LinkedHashMap<>();
        for (String node : jobs.keySet()) {
            copiedDependencies.put(node, Collections.unmodifiableSet(new LinkedHashSet<>(
                    builder.dependencies.getOrDefault(node, Set.of()))));
        }
        dependencies = Collections.unmodifiableMap(copiedDependencies);
        failurePolicy = builder.failurePolicy;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public Set<String> nodes() {
        return jobs.keySet();
    }

    public Optional<Job> job(String node) {
        Objects.requireNonNull(node, "node");
        return Optional.ofNullable(jobs.get(node));
    }

    public Set<String> dependenciesOf(String node) {
        Objects.requireNonNull(node, "node");
        Set<String> result = dependencies.get(node);
        if (result == null) {
            throw new IllegalArgumentException("Unknown workflow node: " + node);
        }
        return result;
    }

    public WorkflowFailurePolicy failurePolicy() {
        return failurePolicy;
    }

    /** Returns deterministic Graphviz DOT with dependency-to-dependent edges. */
    public String toDot() {
        StringBuilder dot = new StringBuilder("digraph \"")
                .append(escape(name)).append("\" {\n");
        for (String node : jobs.keySet()) {
            dot.append("  \"").append(escape(node)).append("\";\n");
        }
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            for (String dependency : entry.getValue()) {
                dot.append("  \"").append(escape(dependency)).append("\" -> \"")
                        .append(escape(entry.getKey())).append("\";\n");
            }
        }
        return dot.append("}\n").toString();
    }

    Map<String, Job> jobs() {
        return jobs;
    }

    Map<String, Set<String>> dependencies() {
        return dependencies;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    public static final class Builder {
        private final String name;
        private final Map<String, Job> jobs = new LinkedHashMap<>();
        private final Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        private WorkflowFailurePolicy failurePolicy = WorkflowFailurePolicy.FAIL_WORKFLOW;

        private Builder(String name) {
            this.name = requireName(name, "Workflow name");
        }

        public Builder job(String node, Runnable task) {
            String nodeName = requireName(node, "Node name");
            return job(nodeName, Job.builder(nodeName).task(task).build());
        }

        public Builder job(String node, Job job) {
            String nodeName = requireName(node, "Node name");
            Objects.requireNonNull(job, "job");
            if (jobs.putIfAbsent(nodeName, job) != null) {
                throw new IllegalArgumentException("Duplicate workflow node: " + nodeName);
            }
            return this;
        }

        public Builder dependsOn(String node, String... requiredNodes) {
            String nodeName = requireName(node, "Node name");
            Objects.requireNonNull(requiredNodes, "requiredNodes");
            Set<String> values = dependencies.computeIfAbsent(nodeName,
                    ignored -> new LinkedHashSet<>());
            for (String required : requiredNodes) {
                values.add(requireName(required, "Dependency name"));
            }
            return this;
        }

        public Builder failurePolicy(WorkflowFailurePolicy policy) {
            failurePolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        public Workflow build() {
            if (jobs.isEmpty()) {
                throw new IllegalStateException("A workflow requires at least one job");
            }
            validateReferences();
            detectCycle();
            return new Workflow(this);
        }

        private void validateReferences() {
            for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
                if (!jobs.containsKey(entry.getKey())) {
                    throw new IllegalStateException("Unknown workflow node: " + entry.getKey());
                }
                for (String dependency : entry.getValue()) {
                    if (!jobs.containsKey(dependency)) {
                        throw new IllegalStateException("Unknown dependency '" + dependency
                                + "' for node '" + entry.getKey() + "'");
                    }
                }
            }
        }

        private void detectCycle() {
            Map<String, Visit> visits = new LinkedHashMap<>();
            Deque<String> path = new ArrayDeque<>();
            for (String node : jobs.keySet()) {
                if (visits.get(node) == null) {
                    visit(node, visits, path);
                }
            }
        }

        private void visit(String node, Map<String, Visit> visits, Deque<String> path) {
            visits.put(node, Visit.VISITING);
            path.addLast(node);
            for (String dependency : dependencies.getOrDefault(node, Set.of())) {
                Visit visit = visits.get(dependency);
                if (visit == Visit.VISITING) {
                    List<String> cycle = new ArrayList<>();
                    boolean collecting = false;
                    for (String pathNode : path) {
                        if (pathNode.equals(dependency)) {
                            collecting = true;
                        }
                        if (collecting) {
                            cycle.add(pathNode);
                        }
                    }
                    cycle.add(dependency);
                    throw new WorkflowCycleException(cycle);
                }
                if (visit == null) {
                    visit(dependency, visits, path);
                }
            }
            path.removeLast();
            visits.put(node, Visit.VISITED);
        }

        private static String requireName(String value, String label) {
            Objects.requireNonNull(value, label);
            if (value.isBlank()) {
                throw new IllegalArgumentException(label + " must not be blank");
            }
            return value;
        }

        private enum Visit {
            VISITING,
            VISITED
        }
    }
}
