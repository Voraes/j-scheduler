package io.github.voraes.jscheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkflowSchedulerTest {
    private Scheduler scheduler;

    @AfterEach
    void stopScheduler() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void executesSimpleSequenceInDependencyOrder() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        scheduler = Scheduler.builder().platformThreads(2).build();
        Workflow workflow = Workflow.builder("sequence")
                .job("a", () -> order.add("a"))
                .job("b", () -> order.add("b"))
                .job("c", () -> order.add("c"))
                .dependsOn("b", "a")
                .dependsOn("c", "b")
                .build();

        WorkflowResult result = await(scheduler.schedule(workflow));

        assertEquals(WorkflowStatus.SUCCEEDED, result.status());
        assertEquals(List.of("a", "b", "c"), order);
        assertTrue(result.executions().values().stream()
                .allMatch(value -> value.status() == WorkflowNodeStatus.SUCCEEDED));
        assertFalse(result.duration().isNegative());
    }

    @Test
    void runsFanOutConcurrentlyAndWaitsAtFanIn() throws Exception {
        CountDownLatch branchesStarted = new CountDownLatch(2);
        CountDownLatch releaseBranches = new CountDownLatch(1);
        AtomicBoolean joinedTooEarly = new AtomicBoolean();
        List<String> order = new CopyOnWriteArrayList<>();
        scheduler = Scheduler.builder().platformThreads(2).build();
        Workflow workflow = Workflow.builder("fan-out-in")
                .job("root", () -> order.add("root"))
                .job("left", () -> branch(branchesStarted, releaseBranches, order, "left"))
                .job("right", () -> branch(branchesStarted, releaseBranches, order, "right"))
                .job("join", () -> {
                    joinedTooEarly.set(releaseBranches.getCount() != 0);
                    order.add("join");
                })
                .dependsOn("left", "root")
                .dependsOn("right", "root")
                .dependsOn("join", "left", "right")
                .build();

        WorkflowHandle handle = scheduler.schedule(workflow);
        assertTrue(branchesStarted.await(1, TimeUnit.SECONDS));
        assertFalse(handle.completion().toCompletableFuture().isDone());
        releaseBranches.countDown();
        WorkflowResult result = await(handle);

        assertEquals(WorkflowStatus.SUCCEEDED, result.status());
        assertFalse(joinedTooEarly.get());
        assertEquals("root", order.get(0));
        assertEquals("join", order.get(order.size() - 1));
    }

    @Test
    void skipDependentsContinuesIndependentBranches() throws Exception {
        CountDownLatch independentFinished = new CountDownLatch(1);
        AtomicBoolean dependentRan = new AtomicBoolean();
        scheduler = Scheduler.builder().platformThreads(2).build();
        Workflow workflow = Workflow.builder("continue-independent")
                .job("failure", () -> { throw new IllegalStateException("boom"); })
                .job("dependent", () -> dependentRan.set(true))
                .job("independent", independentFinished::countDown)
                .job("independent-child", () -> { })
                .dependsOn("dependent", "failure")
                .dependsOn("independent-child", "independent")
                .failurePolicy(WorkflowFailurePolicy.SKIP_DEPENDENTS)
                .build();

        WorkflowResult result = await(scheduler.schedule(workflow));

        assertEquals(WorkflowStatus.FAILED, result.status());
        assertTrue(independentFinished.await(1, TimeUnit.SECONDS));
        assertFalse(dependentRan.get());
        assertEquals(WorkflowNodeStatus.FAILED,
                result.execution("failure").orElseThrow().status());
        assertEquals(WorkflowNodeStatus.SKIPPED,
                result.execution("dependent").orElseThrow().status());
        assertEquals(WorkflowNodeStatus.SUCCEEDED,
                result.execution("independent-child").orElseThrow().status());
    }

    @Test
    void failWorkflowInterruptsActiveIndependentBranch() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        scheduler = Scheduler.builder().platformThreads(2).build();
        Workflow workflow = Workflow.builder("fail-fast")
                .job("slow", () -> {
                    slowStarted.countDown();
                    try {
                        Thread.sleep(Duration.ofMinutes(1));
                    } catch (InterruptedException expected) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                })
                .job("failure", () -> {
                    awaitLatch(slowStarted);
                    throw new IllegalStateException("boom");
                })
                .job("never", () -> { })
                .dependsOn("never", "slow")
                .failurePolicy(WorkflowFailurePolicy.FAIL_WORKFLOW)
                .build();

        WorkflowResult result = await(scheduler.schedule(workflow));

        assertEquals(WorkflowStatus.FAILED, result.status());
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        assertEquals(WorkflowNodeStatus.CANCELLED,
                result.execution("slow").orElseThrow().status());
        assertEquals(WorkflowNodeStatus.SKIPPED,
                result.execution("never").orElseThrow().status());
    }

    @Test
    void workflowNodesComposeRetriesBeforeUnlockingDependents() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean dependentRan = new AtomicBoolean();
        scheduler = Scheduler.builder().platformThreads(1).build();
        Job retried = Job.builder("retried")
                .task(() -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("transient");
                    }
                })
                .retry(RetryPolicy.fixedDelay().maxAttempts(2).delay(Duration.ZERO).build())
                .build();
        Workflow workflow = Workflow.builder("retry-workflow")
                .job("retried", retried)
                .job("dependent", () -> dependentRan.set(true))
                .dependsOn("dependent", "retried")
                .build();

        WorkflowResult result = await(scheduler.schedule(workflow));

        assertEquals(WorkflowStatus.SUCCEEDED, result.status());
        assertEquals(2, attempts.get());
        assertTrue(dependentRan.get());
        assertEquals(2, result.execution("retried").orElseThrow().execution()
                .orElseThrow().attempt());
    }

    @Test
    void timedOutNodeFailsWorkflowAndSkipsDependent() throws Exception {
        AtomicBoolean dependentRan = new AtomicBoolean();
        scheduler = Scheduler.builder().platformThreads(1).build();
        Job timed = Job.builder("timed")
                .task(() -> {
                    try {
                        Thread.sleep(Duration.ofMinutes(1));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                })
                .timeout(Duration.ofMillis(30))
                .build();
        Workflow workflow = Workflow.builder("timeout-workflow")
                .job("timed", timed)
                .job("dependent", () -> dependentRan.set(true))
                .dependsOn("dependent", "timed")
                .build();

        WorkflowResult result = await(scheduler.schedule(workflow));

        assertEquals(WorkflowStatus.FAILED, result.status());
        assertFalse(dependentRan.get());
        assertEquals(JobStatus.TIMED_OUT, result.execution("timed").orElseThrow()
                .execution().orElseThrow().status());
    }

    @Test
    void cancellingWorkflowCancelsRunningAndPendingNodes() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean pendingRan = new AtomicBoolean();
        scheduler = Scheduler.builder().platformThreads(1).build();
        Workflow workflow = Workflow.builder("cancel")
                .job("running", () -> {
                    started.countDown();
                    try {
                        Thread.sleep(Duration.ofMinutes(1));
                    } catch (InterruptedException expected) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                })
                .job("pending", () -> pendingRan.set(true))
                .dependsOn("pending", "running")
                .build();
        WorkflowHandle handle = scheduler.schedule(workflow);
        assertTrue(started.await(1, TimeUnit.SECONDS));

        assertTrue(handle.cancel(true));
        assertFalse(handle.cancel(true));
        WorkflowResult result = await(handle);

        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        assertEquals(WorkflowStatus.CANCELLED, result.status());
        assertFalse(pendingRan.get());
        assertEquals(WorkflowNodeStatus.CANCELLED,
                result.execution("pending").orElseThrow().status());
    }

    @Test
    void schedulerShutdownCompletesWorkflowAsCancelled() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        scheduler = Scheduler.builder().platformThreads(1).build();
        Workflow workflow = Workflow.builder("shutdown")
                .job("running", () -> {
                    started.countDown();
                    try {
                        Thread.sleep(Duration.ofMinutes(1));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                })
                .job("pending", () -> { })
                .dependsOn("pending", "running")
                .build();
        WorkflowHandle handle = scheduler.schedule(workflow);
        assertTrue(started.await(1, TimeUnit.SECONDS));

        scheduler.shutdown();
        WorkflowResult result = await(handle);

        assertEquals(WorkflowStatus.CANCELLED, result.status());
        assertEquals(WorkflowNodeStatus.CANCELLED,
                result.execution("running").orElseThrow().status());
    }

    @Test
    void separateRunsHaveDistinctIdentityAndResults() throws Exception {
        scheduler = Scheduler.builder().virtualThreads().maxConcurrentJobs(4).build();
        Workflow workflow = Workflow.builder("repeatable").job("node", () -> { }).build();

        WorkflowHandle first = scheduler.schedule(workflow);
        WorkflowHandle second = scheduler.schedule(workflow);
        WorkflowResult firstResult = await(first);
        WorkflowResult secondResult = await(second);

        assertNotEquals(first.id(), second.id());
        assertEquals(first.id(), firstResult.id());
        assertEquals(second.id(), secondResult.id());
    }

    private static void branch(CountDownLatch started, CountDownLatch release, List<String> order,
            String name) {
        started.countDown();
        awaitLatch(release);
        order.add(name);
    }

    private static WorkflowResult await(WorkflowHandle handle) throws Exception {
        return handle.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out awaiting test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
