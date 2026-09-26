# Failure scenarios

Each scenario is run twice: once against the plain limiter (503 on error) and
once per `FAIL_MODE` after Exercise 5. Record measured numbers, not
impressions. All scenarios assume `make up` and a steady load in another
terminal:

    make loadgen ARGS="-rps 100 -duration 90s -users 5 -label <scenario>"

Watch Grafana (http://localhost:3000) while the fault is active.

| # | scenario | inject | hypothesis | measure |
|---|---|---|---|---|
| 1 | Replica dies | `make chaos-kill SVC=api-2` | Remaining replicas keep enforcing the same global budget; allowed rps unchanged, only distribution shifts | allowed rps before/after; `by_instance` in loadgen summary |
| 2 | Redis hard down | `make chaos-redis-down` | No resilience: every request 503 quickly (Lettuce rejects commands while disconnected). Open: all allowed. Closed: all 429. Local: ~limit allowed, enforced per replica | error rate, allowed rps, p99 |
| 3 | Redis partition | `make chaos-partition STATE=on` | No resilience: every request 503 but only after the 50ms timeout; p99 jumps by exactly the timeout. With breaker: only the first N requests pay the timeout, then one probe per cooldown | p50/p99 during fault; `backend_errors_total` slope |
| 4 | Redis slow | `make chaos-latency MS=30` then `MS=80` | 30ms: checks succeed, request latency +30ms *per check* (`/api/cheap` matches 2 rules). 80ms: exceeds the 50ms timeout, behaves like a partition | check latency histogram vs `REDIS_TIMEOUT` |
| 5 | Redis restart (state loss) | `chaos-redis-down` stop then start | Budgets reset: clients mid-window get a fresh allowance. Quantify the over-admission | allowed count in the 10s after restart vs steady state |
| 6 | Clock skew (memory backend) | `BACKEND=memory` + `CLOCK_SKEW=3s` on api-3 | Fixed window on api-3 opens boundaries 3s early; a client alternating replicas over-admits. Redis backend immune | boundary-mode loadgen: allowed per boundary vs limit |
| 7 | Hot key | `make loadgen ARGS="-users 1 -rps 2000"` | Single key serialises on one Redis hash; measure ceiling | check p99, Redis CPU (`docker stats`) |

Reset between scenarios with `make chaos-reset` and `make flush`.

## Results

_To be filled in as scenarios are run. Template per scenario:_

### 1. Replica dies

- Setup:
- Observed (no resilience):
- Observed (`FAIL_MODE=open|closed|local`):
- Conclusion:
