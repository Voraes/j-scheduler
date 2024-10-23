import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class BackgroundTaskSchedulerTest {
    private BackgroundTaskScheduler scheduler;

    @BeforeEach
    public void setUp() {
        scheduler = new BackgroundTaskScheduler(3);
    }

    @AfterEach
    public void tearDown() {
        scheduler.shutdown();
    }

    @Test
    public void testScheduleTask() throws InterruptedException {
        StringBuilder output = new StringBuilder();

        Runnable task = () -> output.append("Task executed!");

        scheduler.scheduleTask(task, 1, TimeUnit.SECONDS, 1);

        Thread.sleep(1500);

        assertEquals("Task executed!", output.toString(), "The task should have been executed");
    }

    @Test
    public void testTaskPrioritization() throws InterruptedException {
        StringBuilder executionOrder = new StringBuilder();

        Runnable lowPriorityTask = () -> executionOrder.append("Low ");
        Runnable highPriorityTask = () -> executionOrder.append("High ");

        scheduler.scheduleTask(lowPriorityTask, 1, TimeUnit.SECONDS, 1);  // Lower priority
        scheduler.scheduleTask(highPriorityTask, 1, TimeUnit.SECONDS, 2); // Higher priority

        Thread.sleep(1500);

        // Higher priority task should execute first
        assertEquals("High Low ", executionOrder.toString(), "Tasks should execute in priority order");
    }

    @Test
    public void testAdjustTaskPriority() throws InterruptedException {
        StringBuilder executionOrder = new StringBuilder();

        Runnable lowPriorityTask = () -> executionOrder.append("Low ");
        Runnable highPriorityTask = () -> executionOrder.append("High ");

        scheduler.scheduleTask(lowPriorityTask, 1, TimeUnit.SECONDS, 1); // Lower priority
        scheduler.scheduleTask(highPriorityTask, 1, TimeUnit.SECONDS, 2); // Higher priority

        scheduler.adjustTaskPriority(lowPriorityTask, 3);

        Thread.sleep(3500);

        // Low priority task should execute first after adjustment
        assertEquals("Low High ", executionOrder.toString(), "Tasks should execute in adjusted priority order");
    }

    @Test
    public void testErrorHandling() throws InterruptedException {
        StringBuilder output = new StringBuilder();

        // Create a task that throws an exception
        Runnable failingTask = () -> {
            throw new RuntimeException("Simulated failure");
        };
        Runnable successTask = () -> output.append("Success");

        scheduler.scheduleTask(failingTask, 1, TimeUnit.SECONDS, 1); // Task that will fail
        scheduler.scheduleTask(successTask, 2, TimeUnit.SECONDS, 2); // Successful task

        Thread.sleep(2500);

        // Verify that the success task still executed, even after a failure
        assertEquals("Success", output.toString(), "The successful task should still run even after the failure");
    }
}
