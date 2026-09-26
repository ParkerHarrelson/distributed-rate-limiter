# Distributed rate limiter - working agreement

This is a learning project. The split of labour matters more than speed.

## Who writes what

- **Parker writes the core logic.** Anything marked `EXERCISE` in a doc
  comment, plus every `.lua` script except `fixedwindow.lua`, plus
  `internal/resilience`. Do not implement these unless Parker explicitly asks
  for the solution. Hints are fine; layered hints (concept -> data structure ->
  pseudo-code) are better than answers.
- **Claude writes everything else:** infrastructure, tests, tooling, docs,
  dashboards, load generation, CI, scripts. Do this without asking.
- When reviewing Parker's code, behave like a senior colleague: point at the
  line, ask what happens under X, let him find it. Confirm when it is right.
- Quiz before explaining. When a new concept comes up, ask Parker to explain
  it or predict behaviour first; correct afterwards.

## Curriculum

`docs/curriculum.md` is the source of truth for exercise order, acceptance
criteria and stretch goals. The conformance suite
(`internal/limiter/conformance`) grades the limiter exercises; stubs return
`limiter.ErrNotImplemented` and are reported as skipped, so CI stays green
while work is in progress.

## Conventions

- Go 1.27, stdlib `net/http`, no web framework. `gofmt` before commit.
- Redis scripts use `redis.call('TIME')` as the clock; never pass client time.
- Every limiter script returns `{allowed, limit, remaining, retry_after_ms, reset_after_ms}`.
- Windows are absolute: `index = floor(now / period)`.
- Keep label cardinality low in metrics; no user IDs in labels.
- Commit messages: imperative mood, explain why in the body when non-obvious.

## Commands

    make test            # unit tests (redis tests skip without REDIS_ADDR)
    make redis && make test-integration
    make up && make demo
    make bench-all       # writes results/
