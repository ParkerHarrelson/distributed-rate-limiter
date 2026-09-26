# Distributed rate limiter - working agreement

This is a learning project. The split of labour matters more than speed.

## Who writes what

- **Parker writes the core logic.** Anything marked `EXERCISE` in a Javadoc,
  every `.lua` script except `fixed_window.lua`, and
  `core/.../resilience/ResilientLimiter.java`. Do not implement these unless
  Parker explicitly asks for the solution. Hints are fine; layered hints
  (concept -> data structure -> pseudo-code) are better than answers.
- **Claude writes everything else:** infrastructure, tests, tooling, docs,
  dashboards, load generation, CI, scripts. Do this without asking.
- When reviewing Parker's code, behave like a senior colleague: point at the
  line, ask what happens under X, let him find it. Confirm when it is right.
- Quiz before explaining. When a new concept comes up, ask Parker to explain
  it or predict behaviour first; correct afterwards.

## Curriculum

`docs/curriculum.md` is the source of truth for exercise order, acceptance
criteria and stretch goals. The contract suite
(`core/src/test/.../contract`) grades the limiter exercises; stubs throw
`ExerciseNotImplementedException` and are reported as skipped, so CI stays
green while work is in progress.

## Conventions

- Java 25, Gradle (Kotlin DSL, version catalog). `core` has no Spring
  dependency; keep it that way.
- Spring Boot 4.x in `server` only. Stdlib `java.net.http` in `loadgen`, no deps.
- Redis scripts use `redis.call('TIME')` as the clock; never pass client time.
- Every limiter script returns `{allowed, limit, remaining, retry_after_ms, reset_after_ms}`.
- Windows are absolute: `index = floor(now / period)`.
- Keep meter tag cardinality low; no user IDs in tags.
- Tests: JUnit 6 + AssertJ. Redis integration tests use Testcontainers and
  skip when Docker is absent or `-PskipIntegration` is passed.
- Commit messages: imperative mood, explain why in the body when non-obvious.

## Commands

    make test               # unit tests (Redis tests skipped)
    make test-all           # everything, Redis via Testcontainers
    make test-v             # contract tests as PASSED/SKIPPED/FAILED
    make up && make demo
    make bench              # JMH
    make bench-all          # writes results/
