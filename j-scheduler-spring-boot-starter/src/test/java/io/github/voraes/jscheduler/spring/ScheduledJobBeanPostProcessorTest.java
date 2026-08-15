package io.github.voraes.jscheduler.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ScheduledJobBeanPostProcessorTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JSchedulerAutoConfiguration.class));

    @BeforeEach
    void reset() {
        AnnotatedJobs.runs.set(0);
        AnnotatedJobs.ran = new CountDownLatch(2);
    }

    @Test
    void schedulesAnnotatedMethodsAndUsesFixedDelay() {
        contextRunner.withUserConfiguration(ValidConfiguration.class).run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            assertThat(AnnotatedJobs.ran.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(AnnotatedJobs.runs.get()).isGreaterThanOrEqualTo(2);
        });
    }

    @Test
    void declarativeSchedulingCanBeDisabled() {
        contextRunner.withUserConfiguration(ValidConfiguration.class)
                .withPropertyValues("j-scheduler.declarative.enabled=false")
                .run(context -> assertThat(AnnotatedJobs.ran.await(100, TimeUnit.MILLISECONDS))
                        .isFalse());
    }

    @Test
    void invalidMethodSignatureFailsAtStartup() {
        contextRunner.withUserConfiguration(InvalidConfiguration.class).run(context -> {
            assertThat(context.getStartupFailure()).isNotNull();
            assertThat(context.getStartupFailure()).hasStackTraceContaining(
                    "@ScheduledJob method must be");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class ValidConfiguration {
        @Bean
        AnnotatedJobs annotatedJobs() {
            return new AnnotatedJobs();
        }
    }

    static class AnnotatedJobs {
        private static final AtomicInteger runs = new AtomicInteger();
        private static CountDownLatch ran;

        @ScheduledJob(name = "annotated-test", fixedDelay = "10ms", priority = 5)
        void run() {
            runs.incrementAndGet();
            ran.countDown();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class InvalidConfiguration {
        @Bean
        InvalidJobs invalidJobs() {
            return new InvalidJobs();
        }
    }

    static class InvalidJobs {
        @ScheduledJob(fixedRate = "1s")
        String invalid(String argument) {
            return argument;
        }
    }
}
