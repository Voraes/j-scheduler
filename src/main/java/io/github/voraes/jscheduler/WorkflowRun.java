package io.github.voraes.jscheduler;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** One graph run. Mutable graph state is serialized on the orchestration executor. */
final class WorkflowRun implements WorkflowHandle {
    private final WorkflowId id = WorkflowId.random();
    private final Scheduler scheduler;
    private final Workflow workflow;
    private final Clock clock;
    private final ExecutorService executor;
    private final CompletableFuture<WorkflowResult> completion = new CompletableFuture<>();
    private final Map<String, NodeRun> nodes = new LinkedHashMap<>();
    private final Map<String, Set<String>> dependents = new LinkedHashMap<>();
    private final Map<String, JobHandle> active = new LinkedHashMap<>();
    private final Instant startedAt;
    private volatile WorkflowStatus status = WorkflowStatus.SCHEDULED;
    private volatile WorkflowResult result;
    private boolean cancellationRequested;
    private boolean failedFast;

    WorkflowRun(Scheduler scheduler, Workflow workflow, Clock clock) {
        this.scheduler = scheduler;
        this.workflow = workflow;
        this.clock = clock;
        startedAt = clock.instant();
        executor = Executors.newSingleThreadExecutor(Thread.ofVirtual()
                .name("j-scheduler-workflow-" + id.value() + "-", 0).factory());
        for (String node : workflow.nodes()) {
            nodes.put(node, new NodeRun());
            dependents.put(node, new LinkedHashSet<>());
        }
        for (Map.Entry<String, Set<String>> entry : workflow.dependencies().entrySet()) {
            for (String dependency : entry.getValue()) {
                dependents.get(dependency).add(entry.getKey());
            }
        }
    }

    WorkflowHandle start() {
        executor.execute(this::startGraph);
        return this;
    }

    private synchronized void startGraph() {
        if (result != null) {
            return;
        }
        status = WorkflowStatus.RUNNING;
        scheduleEligibleNodes();
        finishIfComplete();
    }

    private void scheduleEligibleNodes() {
        if (cancellationRequested || failedFast) {
            return;
        }
        boolean changedState;
        do {
            changedState = false;
            List<String> eligible = new ArrayList<>();
            for (String node : workflow.nodes()) {
                NodeRun run = nodes.get(node);
                if (run.status != WorkflowNodeStatus.PENDING) {
                    continue;
                }
                Set<String> dependencies = workflow.dependenciesOf(node);
                boolean blocked = dependencies.stream().map(nodes::get)
                        .anyMatch(dependency -> dependency.status == WorkflowNodeStatus.FAILED
                                || dependency.status == WorkflowNodeStatus.SKIPPED
                                || dependency.status == WorkflowNodeStatus.CANCELLED);
                if (blocked) {
                    skipNode(run);
                    changedState = true;
                } else if (dependencies.stream().map(nodes::get)
                        .allMatch(dependency -> dependency.status == WorkflowNodeStatus.SUCCEEDED)) {
                    eligible.add(node);
                }
            }
            for (String node : eligible) {
                scheduleNode(node);
                changedState = true;
            }
        } while (changedState && !cancellationRequested && !failedFast);
    }

    private void scheduleNode(String node) {
        NodeRun run = nodes.get(node);
        if (run.status != WorkflowNodeStatus.PENDING) {
            return;
        }
        run.status = WorkflowNodeStatus.RUNNING;
        run.startedAt = clock.instant();
        try {
            JobHandle handle = scheduler.execute(workflow.jobs().get(node));
            active.put(node, handle);
            handle.completion().whenCompleteAsync(
                    (execution, failure) -> nodeCompleted(node, execution, failure), executor);
        } catch (RejectedExecutionException rejected) {
            run.status = WorkflowNodeStatus.CANCELLED;
            run.completedAt = clock.instant();
        }
    }

    private synchronized void nodeCompleted(String node, JobExecution execution, Throwable failure) {
        if (result != null) {
            return;
        }
        active.remove(node);
        NodeRun run = nodes.get(node);
        run.completedAt = clock.instant();
        run.execution = execution;
        if (cancellationRequested) {
            run.status = WorkflowNodeStatus.CANCELLED;
        } else if (failure != null) {
            run.status = WorkflowNodeStatus.FAILED;
        } else {
            run.status = toNodeStatus(execution.status());
        }

        if (run.status == WorkflowNodeStatus.FAILED
                || run.status == WorkflowNodeStatus.SKIPPED) {
            handleFailure(node);
        }
        scheduleEligibleNodes();
        finishIfComplete();
    }

    private WorkflowNodeStatus toNodeStatus(JobStatus jobStatus) {
        return switch (jobStatus) {
            case SUCCEEDED -> WorkflowNodeStatus.SUCCEEDED;
            case CANCELLED -> WorkflowNodeStatus.CANCELLED;
            case SKIPPED -> WorkflowNodeStatus.SKIPPED;
            case FAILED, TIMED_OUT -> WorkflowNodeStatus.FAILED;
            case SCHEDULED, READY, RUNNING -> throw new IllegalStateException(
                    "Job completion was not terminal: " + jobStatus);
        };
    }

    private void handleFailure(String node) {
        if (workflow.failurePolicy() == WorkflowFailurePolicy.FAIL_WORKFLOW) {
            failedFast = true;
            Instant now = clock.instant();
            for (NodeRun run : nodes.values()) {
                if (run.status == WorkflowNodeStatus.PENDING) {
                    run.status = WorkflowNodeStatus.SKIPPED;
                    run.completedAt = now;
                }
            }
            for (JobHandle handle : List.copyOf(active.values())) {
                handle.cancel(true);
            }
        } else {
            skipDescendants(node);
        }
    }

    private void skipDescendants(String failedNode) {
        Deque<String> pending = new ArrayDeque<>(dependents.get(failedNode));
        Set<String> visited = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            String descendant = pending.removeFirst();
            if (!visited.add(descendant)) {
                continue;
            }
            NodeRun run = nodes.get(descendant);
            if (run.status == WorkflowNodeStatus.PENDING) {
                skipNode(run);
            }
            pending.addAll(dependents.get(descendant));
        }
    }

    private void skipNode(NodeRun run) {
        run.status = WorkflowNodeStatus.SKIPPED;
        run.completedAt = clock.instant();
    }

    private synchronized void finishIfComplete() {
        boolean unfinished = nodes.values().stream().anyMatch(run ->
                run.status == WorkflowNodeStatus.PENDING
                        || run.status == WorkflowNodeStatus.RUNNING);
        if (unfinished || result != null) {
            return;
        }
        WorkflowStatus finalStatus;
        if (cancellationRequested) {
            finalStatus = WorkflowStatus.CANCELLED;
        } else if (nodes.values().stream()
                .anyMatch(run -> run.status == WorkflowNodeStatus.FAILED)) {
            finalStatus = WorkflowStatus.FAILED;
        } else if (nodes.values().stream()
                .anyMatch(run -> run.status == WorkflowNodeStatus.CANCELLED)) {
            finalStatus = WorkflowStatus.CANCELLED;
        } else if (nodes.values().stream()
                .anyMatch(run -> run.status == WorkflowNodeStatus.SKIPPED)) {
            finalStatus = WorkflowStatus.FAILED;
        } else {
            finalStatus = WorkflowStatus.SUCCEEDED;
        }
        Instant completedAt = clock.instant();
        Map<String, WorkflowNodeResult> nodeResults = new LinkedHashMap<>();
        for (Map.Entry<String, NodeRun> entry : nodes.entrySet()) {
            NodeRun run = entry.getValue();
            nodeResults.put(entry.getKey(), new WorkflowNodeResult(entry.getKey(), run.status,
                    Optional.ofNullable(run.execution), Optional.ofNullable(run.startedAt),
                    run.completedAt == null ? completedAt : run.completedAt));
        }
        status = finalStatus;
        result = new WorkflowResult(id, workflow.name(), finalStatus, startedAt, completedAt,
                nodeResults);
        WorkflowResult terminalResult = result;
        Thread.ofVirtual().name("j-scheduler-workflow-completion-" + id.value())
                .start(() -> completion.complete(terminalResult));
        executor.shutdown();
    }

    @Override
    public WorkflowId id() {
        return id;
    }

    @Override
    public String name() {
        return workflow.name();
    }

    @Override
    public WorkflowStatus status() {
        return status;
    }

    @Override
    public Optional<WorkflowResult> result() {
        return Optional.ofNullable(result);
    }

    @Override
    public CompletionStage<WorkflowResult> completion() {
        return completion.minimalCompletionStage();
    }

    @Override
    public synchronized boolean cancel(boolean mayInterruptIfRunning) {
        if (result != null || cancellationRequested) {
            return false;
        }
        cancellationRequested = true;
        status = WorkflowStatus.CANCELLED;
        Instant now = clock.instant();
        for (NodeRun run : nodes.values()) {
            if (run.status == WorkflowNodeStatus.PENDING) {
                run.status = WorkflowNodeStatus.CANCELLED;
                run.completedAt = now;
            }
        }
        for (JobHandle handle : List.copyOf(active.values())) {
            handle.cancel(mayInterruptIfRunning);
        }
        finishIfComplete();
        return true;
    }

    private static final class NodeRun {
        private WorkflowNodeStatus status = WorkflowNodeStatus.PENDING;
        private Instant startedAt;
        private Instant completedAt;
        private JobExecution execution;
    }
}
