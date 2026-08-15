package io.github.voraes.jscheduler.example;

import io.github.voraes.jscheduler.ConcurrencyPolicy;
import io.github.voraes.jscheduler.Job;
import io.github.voraes.jscheduler.RetryPolicy;
import io.github.voraes.jscheduler.Schedule;
import io.github.voraes.jscheduler.Scheduler;
import io.github.voraes.jscheduler.Workflow;
import io.github.voraes.jscheduler.WorkflowFailurePolicy;
import io.github.voraes.jscheduler.spring.ScheduledJob;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** A small billing pipeline illustrating both declarative and programmatic scheduling. */
@Component
final class BillingAutomation {
    private static final Logger LOGGER = LoggerFactory.getLogger(BillingAutomation.class);
    private final Scheduler scheduler;
    private final AtomicBoolean simulateTransientFailure = new AtomicBoolean(true);

    BillingAutomation(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        Job invoiceSync = Job.builder("invoice-sync")
                .task(this::synchronizeInvoices)
                .priority(10)
                .timeout(Duration.ofSeconds(5))
                .retry(RetryPolicy.exponentialBackoff()
                        .maxAttempts(3)
                        .initialDelay(Duration.ofMillis(200))
                        .maxDelay(Duration.ofSeconds(2))
                        .jitter(0.10)
                        .build())
                .concurrency(ConcurrencyPolicy.SKIP_IF_RUNNING)
                .build();
        scheduler.schedule(invoiceSync,
                Schedule.fixedDelay(Duration.ofSeconds(2), Duration.ofSeconds(30)));

        Workflow dailyReconciliation = Workflow.builder("daily-reconciliation")
                .job("load-orders", Job.builder("load-orders")
                        .task(() -> LOGGER.info("Loaded unsettled orders"))
                        .priority(20)
                        .build())
                .job("load-payments", Job.builder("load-payments")
                        .task(() -> LOGGER.info("Loaded captured payments"))
                        .priority(20)
                        .build())
                .job("reconcile", () -> LOGGER.info("Reconciled orders against payments"))
                .job("publish-report", () -> LOGGER.info("Published reconciliation report"))
                .dependsOn("reconcile", "load-orders", "load-payments")
                .dependsOn("publish-report", "reconcile")
                .failurePolicy(WorkflowFailurePolicy.SKIP_DEPENDENTS)
                .build();
        scheduler.schedule(dailyReconciliation);
    }

    @ScheduledJob(name = "billing-heartbeat", initialDelay = "5s",
            fixedDelay = "30s", priority = 1)
    public void reportHeartbeat() {
        LOGGER.info("Billing scheduler heartbeat");
    }

    private void synchronizeInvoices() {
        if (simulateTransientFailure.compareAndSet(true, false)) {
            throw new IllegalStateException("Simulated temporary billing API failure");
        }
        LOGGER.info("Synchronized outstanding invoices");
    }
}
