
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BackgroundTaskScheduler {
    private final ScheduledExecutorService executorService;
    private final PriorityBlockingQueue<PriorityTask> taskQueue;

    public BackgroundTaskScheduler(int threadCount) {
        this.executorService = Executors.newScheduledThreadPool(threadCount);
        this.taskQueue = new PriorityBlockingQueue<>();
    }

    public void scheduleTask(Runnable task, int delay, TimeUnit timeUnit, int priority) {
        PriorityTask priorityTask = new PriorityTask(task, priority);
        taskQueue.add(priorityTask);

        executorService.schedule(() -> {
            try {
                PriorityTask taskToRun = taskQueue.poll(); // Remove from the queue the task with the highest priority
                if (taskToRun != null) {
                    System.out.println("Executing task with priority: " + taskToRun.priority);
                    taskToRun.run();
                }
            } catch (Exception e) {
                System.err.println("Task execution failed: " + e.getMessage());
                e.printStackTrace();
            }
        }, delay, timeUnit);
    }

    public void scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit timeUnit, int priority) {
        PriorityTask priorityTask = new PriorityTask(task, priority);
        taskQueue.add(priorityTask);

        executorService.scheduleAtFixedRate(() -> {
            try {
                PriorityTask taskToRun = taskQueue.poll(); // Remove from the queue the task with the highest priority
                if (taskToRun != null) {
                    System.out.println("Executing task with priority: " + taskToRun.priority);
                    taskToRun.run();
                }
            } catch (Exception e) {
                System.err.println("Task execution failed: " + e.getMessage());
                e.printStackTrace();
            }
        }, initialDelay, period, timeUnit);
    }

    // Schedules a task to run with a fixed delay between executions
    public void scheduleWithFixedDelay(Runnable task, long initialDelay, long delay, TimeUnit timeUnit, int priority) {
        PriorityTask priorityTask = new PriorityTask(task, priority);
        taskQueue.add(priorityTask);

        executorService.scheduleWithFixedDelay(() -> {
            try {
                PriorityTask taskToRun = taskQueue.poll(); // Remove from the queue the task with the highest priority
                if (taskToRun != null) {
                    System.out.println("Executing task with priority: " + taskToRun.priority);
                    taskToRun.run();
                }
            } catch (Exception e) {
                System.err.println("Task execution failed: " + e.getMessage());
                e.printStackTrace();
            }
        }, initialDelay, delay, timeUnit);
    }

    public synchronized void adjustTaskPriority(Runnable task, int newPriority) {
        taskQueue.removeIf(priorityTask -> priorityTask.task.equals(task));
        taskQueue.add(new PriorityTask(task, newPriority));
    }

    public void shutdown() {
        executorService.shutdown();
    }

    public void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    // Task class with priority
    private static class PriorityTask implements Runnable, Comparable<PriorityTask> {
        private final Runnable task;
        private final int priority;

        public PriorityTask(Runnable task, int priority) {
            this.task = task;
            this.priority = priority;
        }

        @Override
        public void run() {
            task.run();
        }

        @Override
        public int compareTo(PriorityTask other) {
            // Higher priority tasks come first
            return Integer.compare(other.priority, this.priority);
        }
    }
}
