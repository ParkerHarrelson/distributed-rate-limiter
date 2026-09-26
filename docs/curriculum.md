# Curriculum

The point of this repository is for Parker to build the parts of a distributed
rate limiter that teach something, with the scaffolding already in place so
that every exercise is runnable, testable and measurable the moment it is
written.

**Grading:** each limiter exercise is accepted when its contract tests pass.
Until then the stub throws `ExerciseNotImplementedException` and the tests are
reported as *skipped*, not failed. Watch progress with:

    make test-v

**How we work:** for each exercise, answer the design questions in
conversation *before* writing code. Then write it, run the tests, and bring
failing output back for review. Code review is Socratic: expect questions,
not patches.

**Where things are:** `core` is framework-free Java 25. The contract suite is
`core/src/test/.../contract`. Concrete tests per algorithm live next to the
implementation they grade.

---

## Exercise 0: warm-up (30-60 min)

Goal: get comfortable with the harness and the concurrency pattern before the
algorithms add conceptual load.

1. Read `core/.../memory/FixedWindowLimiter.java` top to bottom. Be ready to
   explain: why `ConcurrentHashMap.computeIfAbsent` plus a `synchronized`
   per-key object, rather than one `synchronized` method on the limiter? What
   does `sweep()` race against, and how could it over-admit by exactly one?
2. Add a method `reset(String key)` to `FixedWindowLimiter` that clears one
   key's state, and a test for it. It must be safe to call concurrently with
   `allow`.
3. Write `MutableClockTest`: `advance` and `set` behave, and concurrent
   `advance` calls from 16 threads sum correctly.

Acceptance: `make test` green.

---

## Exercise 1: token bucket, in memory

Files: `core/.../memory/TokenBucketLimiter.java` (stub with design questions)
Tests: `TokenBucketLimiterTest` (extends `TokenBucketContract`)

Concept check before coding:

- Explain the difference between *rate* and *burst* to someone who has only
  seen a fixed window. Why can a fixed window not express "100/min but never
  more than 10 at once"?
- A bucket was last touched 3.7 seconds ago at 2.2 tokens; rate is 5 per
  second, capacity 10. How many tokens does it hold now? Show the arithmetic.
- Why is "a `ScheduledExecutorService` task per bucket that adds a token every
  interval" the wrong design? (Two reasons: one about resources, one about
  correctness.)
- `double` tokens vs `long` nanos-of-credit: which do you pick and what does
  the other get wrong? (Hint: `0.1 + 0.2`.)
- Concurrency: synchronized per-key object (like the reference),
  `ConcurrentHashMap.compute` with an immutable record, or a CAS loop on an
  `AtomicReference<State>`? What does each cost under a hot key?

Acceptance: all `TokenBucketContract` tests pass, including
`concurrentCallersNeverExceedCapacity`.

Stretch:
- `make bench`. Compare `hotKey` and `manyKeys` throughput against
  `fixed_window`. Then implement the same bucket with the other concurrency
  pattern and benchmark it too. When does lock-free win, and when does it
  lose to `synchronized`?
- Read `com.google.common.util.concurrent.SmoothRateLimiter` and Bucket4j's
  `LockFreeBucket` *after* yours passes. What did they do that you did not?

Interview angle: "Design a rate limiter" is a standard system design prompt.
After this exercise you should be able to whiteboard the token bucket, name
its two parameters, and explain lazy refill.

---

## Exercise 2: sliding windows, in memory

Files: `core/.../memory/SlidingLogLimiter.java`, `SlidingCounterLimiter.java`
Tests: `SlidingLogLimiterTest`, `SlidingCounterLimiterTest`

### 2a: sliding log (exact)

Concept check:
- What is the memory cost per key, in bytes, for a limit of 1000/min with a
  fully active client, if each entry is a boxed `Long` in an `ArrayDeque`?
  As a primitive `long[]` ring buffer? What about 100 000 such clients?
- Which operations does the log need, and what is the cheapest structure
  that supports them? Consider `ArrayDeque`, a ring buffer, `TreeMap`, and a
  `PriorityQueue`. Why is the heap wrong here?
- Denied requests: log them or not? What behaviour does each choice produce
  for a client that retries aggressively?

### 2b: sliding window counter (approximate)

Concept check:
- Write the estimate formula and explain the assumption it encodes.
- With the previous window full (10/10) and 25% into the current window,
  how many requests will be admitted? Now 75%?
- Derive `retryAfter` for both denial cases (see the stub). The
  `retryAfterIsHonest` test enforces this; a lazy "until next window" fails.
- Cloudflare reported 0.003% misclassification with this scheme. Under what
  traffic shape is the approximation *most* wrong?

Acceptance: both contracts green. Then run
`FixedWindowLimiterTest.boundaryBurstIsAllowed` and your `noBoundaryBurst`
tests side by side and explain the difference in one paragraph; that
paragraph goes in `docs/results.md`.

Stretch: `make bench` for all four. Rank them by throughput and memory, then
rank them by accuracy. Notice they are opposite.

---

## Exercise 3: token bucket in Redis (Lua)

Files: `core/src/main/resources/lua/token_bucket.lua` (Java wrapper is done)
Tests: `RedisTokenBucketLimiterTest` (Testcontainers; `make test-integration`)

This is the heart of the project. Before writing Lua:

- Why can the Java code not do `HGET`, compute, `HSET`? Describe the exact
  interleaving across two replicas that over-admits.
- Redis offers `MULTI/EXEC`, `WATCH`, Lua scripts, and Redis Functions.
  Which give atomicity, which give isolation, and why is Lua the standard
  answer for this problem? How does this compare to `SELECT ... FOR UPDATE`
  or an optimistic `@Version` in JPA?
- Time: the script reads `TIME` inside Redis. What goes wrong if each
  replica passed its own `System.currentTimeMillis()` instead? Is there any
  argument *for* client time? (There is one: think about replication and
  script determinism on old Redis versions.)
- What TTL does a bucket key need? Derive it from the algorithm, do not guess.
- Redis Cluster: why must the script only touch `KEYS[1]`? What would a
  per-user-per-endpoint key look like if you wanted all of a user's keys on
  one shard? (Hash tags: `{user}`.)
- Lettuce multiplexes every caller over one connection. Why is that fine
  here, and when would you want a pool instead?

Acceptance: integration suite green against `redis:7`. Then `make up`,
`ALGORITHM=token_bucket make restart-api`, `make demo`, and confirm the
Grafana dashboard shows the algorithm label change.

Stretch: run `redis-cli --latency` and `redis-cli MONITOR` while the loadgen
runs. How many commands does one `allow` cost? Could it be fewer?

---

## Exercise 4: sliding window counter in Redis

Files: `core/src/main/resources/lua/sliding_counter.lua`
Tests: `RedisSlidingCounterLimiterTest`

Mostly a port of 2b; the new questions are about TTL (the previous window
must survive one period past the current one) and integer replies (Redis
truncates Lua numbers; be deliberate about rounding `retry_after_ms`).

Stretch: implement sliding *log* in Redis with a sorted set
(`ZADD`, `ZREMRANGEBYSCORE`, `ZCARD`, `PEXPIRE`), wire it as `sliding_log`
in `LimiterConfiguration`, and measure memory with
`redis-cli MEMORY USAGE <key>` vs the counter version.

After 3 and 4: run `make bench-all`. Fill in `docs/results.md` with the
steady-state accuracy table, the boundary-burst table, and the latency table.
This is the centrepiece of the README.

---

## Exercise 5: failure modes and the circuit breaker

Files: `core/.../resilience/ResilientLimiter.java`
Tests: `ResilientLimiterTest` (9 tests)

Before coding, run the failure scenarios in `docs/failure-scenarios.md`
against the *current* code (no resilience layer) and record what happens.
You will see 503s and, with the partition scenario, latency equal to the
Redis timeout on every request. That is the problem statement.

Concept check:
- Fail-open vs fail-closed: give one real product where each is correct.
- Why does the breaker exist at all if calls already have a 50ms timeout?
  Do the arithmetic for 1000 rps during a 60s partition.
- Half-open: exactly one probe, or a percentage? What does each choice do to
  Redis when it comes back under full load?
- `localShare`: with 3 replicas and a limit of 100/s, local fallback gives
  each replica 34/s. What happens when a load balancer is not perfectly even?
  Is over- or under-admitting the better failure?
- Which JDK primitive holds breaker state: `volatile` fields, an
  `AtomicReference<BreakerState>` record, or `synchronized`? What is the
  cost of getting `safeUnderConcurrency` wrong in production?

Acceptance: `ResilientLimiterTest` green; then re-run the failure scenarios
with `FAIL_MODE=open`, `closed`, and `local`, and record the measured
behaviour (allowed rps, p99, error rate) in `docs/failure-scenarios.md`.

Compare afterwards with Resilience4j's `CircuitBreaker`: what does it have
that yours does not, and would you need it?

---

## Exercise 6 (stretch): policy composition without over-counting

Files: `server/.../RateLimitFilter.java`, `core/.../policy`

Today, if a request matches rules A and B and A allows but B denies, A's
token is consumed anyway. Options: (1) evaluate all limits in one Lua script
with multiple keys (cluster implications?), (2) check-then-commit with a
two-phase script, (3) accept it and document why. Pick one, justify it, and
if you implement it, write the test that fails on the current code first.

Also: VIP users currently hit *both* `vip-global` and `user-global`. Add rule
precedence or exemption to the policy engine, with tests.

---

## Reading list

- Cloudflare, "How we built rate limiting capable of scaling to millions of
  domains" (the sliding window counter).
- Stripe, "Scaling your API with rate limiters" (token bucket, multiple
  limiter types, fail-open reasoning).
- Figma, "An alternative approach to rate limiting".
- Redis docs: "Scripting with Lua" (atomicity, `redis.call`, script effects
  replication), and "Redis Cluster specification" (hash slots, hash tags).
- Bucket4j source (`LockFreeBucket`) and Guava `SmoothRateLimiter`: two
  production Java token buckets with different concurrency strategies. Read
  *after* Exercise 1.
- Lettuce reference: connection sharing, command timeouts, `disconnectedBehavior`.
- Java Concurrency in Practice, ch. 15 (nonblocking algorithms), if the CAS
  variant of Exercise 1 is unclear.
