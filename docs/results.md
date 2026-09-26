# Measured results

_Populated by `make bench-all` output plus commentary. Empty sections mean the
experiment has not been run yet, usually because the algorithm is still an
exercise stub._

Environment: Docker Desktop on Apple Silicon, 3 API replicas, Redis 7,
loadgen on the host. Numbers are for comparing algorithms against each other,
not absolute throughput claims.

## Steady state: 5 users, each limited to 50/10s, offered 2x

Expected allowed rate: 25 rps aggregate (5 users x 5 rps).

| algorithm | allowed rps | denied rps | p50 ms | p99 ms | notes |
|---|---|---|---|---|---|
| fixed_window | | | | | |
| sliding_counter | | | | | |
| token_bucket | | | | | |

## Boundary burst: 3 users, 50 requests each, 200ms before and after a 10s boundary

Limit is 50/10s, so a perfect limiter admits at most 50 per user across the
two bursts.

| algorithm | allowed before | allowed after | total within ~400ms | over-admission |
|---|---|---|---|---|
| fixed_window | | | | |
| sliding_counter | | | | |
| token_bucket | | | | |

## Micro-benchmarks (in-memory, `make bench`)

| algorithm | hot key ns/op | many keys ns/op | allocs/op | bytes per key |
|---|---|---|---|---|
| fixed_window | | | | |
| token_bucket | | | | |
| sliding_log | | | | |
| sliding_counter | | | | |

## Commentary

_Why the numbers look the way they do; which algorithm you would pick for
which API and why._
