package io.github.voraes.jscheduler.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.voraes.jscheduler.ExecutionMode;
import io.github.voraes.jscheduler.Job;
import io.github.voraes.jscheduler.Scheduler;
import io.github.voraes.jscheduler.SchedulerStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JSchedulerAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JSchedulerAutoConfiguration.class));

    @Test
    void createsVirtualSchedulerFromPropertiesAndClosesItWithContext() {
        AtomicReference<Scheduler> schedulerReference = new AtomicReference<>();

        contextRunner.withPropertyValues(
                "j-scheduler.execution.mode=virtual",
                "j-scheduler.execution.max-concurrent-jobs=7",
                "j-scheduler.shutdown.timeout=1s")
                .run(context -> {
                    assertThat(context).hasSingleBean(Scheduler.class);
                    Scheduler scheduler = context.getBean(Scheduler.class);
                    schedulerReference.set(scheduler);
                    assertThat(scheduler.snapshot().executionMode()).isEqualTo(ExecutionMode.VIRTUAL);
                    assertThat(scheduler.snapshot().maxConcurrentJobs()).isEqualTo(7);
                });

        assertThat(schedulerReference.get().snapshot().status())
                .isEqualTo(SchedulerStatus.TERMINATED);
    }

    @Test
    void backsOffForApplicationScheduler() {
        Scheduler applicationScheduler = Scheduler.builder().platformThreads(1).build();
        try {
            contextRunner.withBean(Scheduler.class, () -> applicationScheduler).run(context -> {
                assertThat(context).hasSingleBean(Scheduler.class);
                assertThat(context.getBean(Scheduler.class)).isSameAs(applicationScheduler);
                assertThat(context).doesNotHaveBean(ManagedScheduler.class);
            });
        } finally {
            if (!applicationScheduler.isShutdown()) {
                applicationScheduler.shutdown();
            }
        }
    }

    @Test
    void publishesMetricsWithoutHighCardinalityTags() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        contextRunner.withBean(SimpleMeterRegistry.class, () -> registry).run(context -> {
            Scheduler scheduler = context.getBean(Scheduler.class);
            scheduler.execute(Job.builder("metrics-job").task(ran::countDown).build());
            assertThat(await(ran)).isTrue();
            awaitCounter(registry, "j.scheduler.jobs.completed", 1.0);
            assertThat(registry.counter("j.scheduler.jobs.scheduled").count()).isEqualTo(1.0);
            assertThat(registry.find("j.scheduler.jobs.completed").counter().getId().getTags())
                    .isEmpty();
            assertThat(registry.get("j.scheduler.jobs.running").gauge().value()).isZero();
        });
    }

    @Test
    void metricsCanBeDisabled() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        contextRunner.withBean(SimpleMeterRegistry.class, () -> registry)
                .withPropertyValues("j-scheduler.metrics.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SchedulerMetrics.class));
    }

    @Test
    void contributesPayloadFreeHealthDetails() {
        contextRunner.run(context -> {
            SchedulerHealthIndicator indicator = context.getBean(SchedulerHealthIndicator.class);
            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsKeys("status", "executionMode",
                    "maxConcurrentJobs", "trackedJobs", "readyOccurrences");
            assertThat(health.getDetails()).doesNotContainKeys("job", "payload");
        });
    }

    @Test
    void customizerCanContributeBuilderBehavior() throws Exception {
        CountDownLatch succeeded = new CountDownLatch(1);
        contextRunner.withBean(JSchedulerCustomizer.class, () -> new JSchedulerCustomizer() {
            @Override
            public void customize(Scheduler.Builder builder) {
                builder.eventListener(event -> {
                    if (event instanceof io.github.voraes.jscheduler.JobEvent.JobSucceeded) {
                        succeeded.countDown();
                    }
                });
            }
        }).run(context -> {
            context.getBean(Scheduler.class).execute(Job.builder("customized")
                    .task(() -> { }).build());
            assertThat(await(succeeded)).isTrue();
        });
    }

    @Test
    void invalidPropertiesFailContextStartup() {
        contextRunner.withPropertyValues("j-scheduler.execution.max-concurrent-jobs=0")
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void awaitCounter(SimpleMeterRegistry registry, String name, double expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (registry.counter(name).count() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(registry.counter(name).count()).isEqualTo(expected);
    }
}
