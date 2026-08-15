package io.github.voraes.jscheduler.spring;

import io.github.voraes.jscheduler.JobEventListener;
import io.github.voraes.jscheduler.Scheduler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Auto-configures J-Scheduler while backing off when an application supplies its own scheduler. */
@AutoConfiguration
@AutoConfigureAfter(name = {
        "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration"
})
@ConditionalOnClass(Scheduler.class)
@EnableConfigurationProperties(JSchedulerProperties.class)
public class JSchedulerAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(Scheduler.class)
    ManagedScheduler jSchedulerManagedInstance(JSchedulerProperties properties,
            ObjectProvider<JobEventListener> listeners,
            ObjectProvider<JSchedulerCustomizer> customizers) {
        return new ManagedScheduler(properties, listeners.orderedStream().toList(),
                customizers.orderedStream().toList());
    }

    @Bean(destroyMethod = "")
    @ConditionalOnBean(ManagedScheduler.class)
    Scheduler jScheduler(ManagedScheduler managed) {
        return managed.scheduler();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnBean(ManagedScheduler.class)
    SchedulerShutdown jSchedulerShutdown(ManagedScheduler managed,
            JSchedulerProperties properties) {
        return new SchedulerShutdown(managed, properties.getShutdown().getTimeout());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnBean(type = "io.micrometer.core.instrument.MeterRegistry")
    @ConditionalOnProperty(prefix = "j-scheduler.metrics", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    static class MetricsConfiguration {
        @Bean
        @ConditionalOnMissingBean
        SchedulerMetrics jSchedulerMetrics(
                ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registries) {
            return new SchedulerMetrics(registries.getObject());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.health.contributor.HealthIndicator")
    @ConditionalOnProperty(prefix = "j-scheduler.health", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    static class HealthConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = "jSchedulerHealthIndicator")
        SchedulerHealthIndicator jSchedulerHealthIndicator(Scheduler scheduler) {
            return new SchedulerHealthIndicator(scheduler);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "j-scheduler.declarative", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    static class DeclarativeConfiguration {
        @Bean
        @ConditionalOnMissingBean
        static ScheduledJobBeanPostProcessor scheduledJobBeanPostProcessor(
                ObjectProvider<Scheduler> schedulers) {
            return new ScheduledJobBeanPostProcessor(schedulers);
        }
    }
}
