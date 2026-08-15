# Contributing to J-Scheduler

Thanks for helping improve J-Scheduler. Bug reports, focused design proposals, documentation fixes,
and well-tested code changes are welcome.

## Development setup

Use JDK 21 or newer and the checked-in Gradle wrapper. No system Gradle installation is required.

```bash
./gradlew clean check
```

The quality gate compiles with `-Xlint:all -Werror`, runs unit and integration tests, applies the
repository Checkstyle rules, and validates public Javadocs. Before submitting concurrency changes,
also run:

```bash
./gradlew stressTest
```

The stress suite is intentionally separate from ordinary unit tests so CI remains bounded.

## Pull requests

- Keep changes focused and explain the behavior being changed.
- Add deterministic tests for fixes and public behavior.
- Preserve interruption status when catching `InterruptedException`.
- State cancellation, timeout, and shutdown semantics explicitly in new APIs.
- Prefer `Duration` to primitive time/unit pairs.
- Keep the core module free of framework and observability dependencies.
- Do not add benchmark claims without reproducible JMH evidence.

## Benchmarks

Benchmarks live in the separate `benchmarks` module and never run as unit tests. See
[`docs/BENCHMARKS.md`](docs/BENCHMARKS.md) before changing or interpreting them.

## Reporting security issues

Please follow [`SECURITY.md`](SECURITY.md) instead of opening a public issue for a vulnerability.
