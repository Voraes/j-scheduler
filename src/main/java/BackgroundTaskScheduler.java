
import io.github.voraes.jscheduler.Job;
import io.github.voraes.jscheduler.JobHandle;
import io.github.voraes.jscheduler.Schedule;
import io.github.voraes.jscheduler.Scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Compatibility facade for the 1.x API. New code should use {@link Scheduler}.
 *
 * @deprecated use {@link Scheduler#builder()}
 */
@Deprecated(since = "2.0", forRemoval = false)
public class BackgroundTaskScheduler {
    private final Scheduler scheduler;
    private final Map<Runnable, Registration> registrations = new ConcurrentHashMap<>();

    public BackgroundTaskScheduler(int threadCount) {
        scheduler = Scheduler.builder().platformThreads(threadCount).build();
    }

    public void scheduleTask(Runnable task, int delay, TimeUnit timeUnit, int priority) {
        register(task, Schedule.delayed(duration(delay, timeUnit)), priority);
    }

    public void scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit timeUnit, int priority) {
        register(task, Schedule.fixedRate(duration(initialDelay, timeUnit), duration(period, timeUnit)), priority);
    }

    public void scheduleWithFixedDelay(Runnable task, long initialDelay, long delay, TimeUnit timeUnit, int priority) {
        register(task, Schedule.fixedDelay(duration(initialDelay, timeUnit), duration(delay, timeUnit)), priority);
    }

    public void adjustTaskPriority(Runnable task, int newPriority) {
        registrations.computeIfPresent(task, (ignored, old) -> {
            Duration remaining = old.handle.nextExecution()
                    .map(next -> Duration.between(Instant.now(), next))
                    .map(value -> value.isNegative() ? Duration.ZERO : value)
                    .orElse(Duration.ZERO);
            old.handle.cancel(false);
            Schedule replacement = withInitialDelay(old.schedule, remaining);
            return createRegistration(task, replacement, newPriority);
        });
    }

    public void shutdown() {
        scheduler.shutdown();
    }

    public void registerShutdownHook() {
        scheduler.registerShutdownHook();
    }

    private void register(Runnable task, Schedule schedule, int priority) {
        Objects.requireNonNull(task, "task");
        Registration registration = createRegistration(task, schedule, priority);
        Registration previous = registrations.put(task, registration);
        if (previous != null) {
            previous.handle.cancel(false);
        }
    }

    private Registration createRegistration(Runnable task, Schedule schedule, int priority) {
        Job job = Job.builder("legacy-task").task(task).priority(priority).build();
        return new Registration(scheduler.schedule(job, schedule), schedule);
    }

    private static Schedule withInitialDelay(Schedule schedule, Duration initialDelay) {
        if (schedule instanceof Schedule.FixedRate fixedRate) {
            return Schedule.fixedRate(initialDelay, fixedRate.period());
        }
        if (schedule instanceof Schedule.FixedDelay fixedDelay) {
            return Schedule.fixedDelay(initialDelay, fixedDelay.delay());
        }
        return Schedule.delayed(initialDelay);
    }

    private static Duration duration(long value, TimeUnit unit) {
        Objects.requireNonNull(unit, "timeUnit");
        if (value < 0) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        return Duration.ofNanos(unit.toNanos(value));
    }

    private record Registration(JobHandle handle, Schedule schedule) { }
}
