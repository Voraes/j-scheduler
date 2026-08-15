package io.github.voraes.jscheduler.example;

import io.github.voraes.jscheduler.Scheduler;
import io.github.voraes.jscheduler.SchedulerSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes aggregate scheduler state without job payloads or identifiers. */
@RestController
@RequestMapping("/api/scheduler")
final class SchedulerController {
    private final Scheduler scheduler;

    SchedulerController(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @GetMapping
    SchedulerSnapshot status() {
        return scheduler.snapshot();
    }
}
