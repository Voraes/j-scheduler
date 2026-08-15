# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and releases use semantic versioning.

## [Unreleased]

### Added

- Java 21 scheduling engine with delayed, fixed-rate, and fixed-delay execution.
- Bounded platform-thread and virtual-thread execution modes.
- Immutable jobs, thread-safe handles, explicit lifecycle states, and structured events.
- Retry, cooperative timeout, concurrency policies, token-bucket rate limiting, and circuit breaking.
- Validated dependency workflows with failure policies and deterministic DOT output.
- Spring Boot starter with declarative scheduling, Micrometer metrics, and Actuator health.
- JMH benchmarks, opt-in concurrency stress tests, Checkstyle, and Maven publication metadata.

### Changed

- Priority now orders only work that is already time-eligible; it never causes future work to run
  early.
- The primary API uses `Duration`, immutable configuration, and returned handles instead of mutable
  scheduled-task objects.
- Virtual-thread execution has an explicit concurrency bound.

### Deprecated

- The default-package `BackgroundTaskScheduler` 1.x compatibility facade.

### Fixed

- Recurring tasks retain their recurrence after the first execution.
- User task and listener failures no longer terminate scheduler infrastructure.

See [`docs/MIGRATION_V2.md`](docs/MIGRATION_V2.md) for source and semantic migration guidance.
