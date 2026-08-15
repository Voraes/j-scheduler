package io.github.voraes.jscheduler.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.voraes.jscheduler.ExecutionMode;
import io.github.voraes.jscheduler.Scheduler;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ExampleApplicationTest {
    @Autowired
    private Scheduler scheduler;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void startsWithTheAutoConfiguredVirtualThreadScheduler() {
        assertThat(scheduler.snapshot().executionMode()).isEqualTo(ExecutionMode.VIRTUAL);
        assertThat(scheduler.snapshot().maxConcurrentJobs()).isEqualTo(200);
        assertThat(meterRegistry.find("j.scheduler.jobs.running").gauge()).isNotNull();
        assertThat(meterRegistry.find("j.scheduler.queue.size").gauge()).isNotNull();
    }
}
