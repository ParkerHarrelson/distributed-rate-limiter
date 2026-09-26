package dev.parkerharrelson.ratelimiter.core.resilience;

import dev.parkerharrelson.ratelimiter.core.Decision;
import dev.parkerharrelson.ratelimiter.core.ExerciseNotImplementedException;
import dev.parkerharrelson.ratelimiter.core.Limit;
import dev.parkerharrelson.ratelimiter.core.Limiter;
import dev.parkerharrelson.ratelimiter.core.LimiterUnavailableException;
import dev.parkerharrelson.ratelimiter.core.clock.MutableClock;
import dev.parkerharrelson.ratelimiter.core.memory.FixedWindowLimiter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Acceptance tests for Exercise 5. Skipped until {@link ResilientLimiter} is implemented. */
class ResilientLimiterTest {

    private static final Limit LIM = Limit.of(10, Duration.ofSeconds(1));
    private static final Instant START = Instant.ofEpochSecond(1_700_000_000L);

    /** A scriptable primary limiter. */
    static final class Flaky implements Limiter {
        final AtomicBoolean failing = new AtomicBoolean();
        final AtomicInteger calls = new AtomicInteger();

        Flaky(boolean failing) {
            this.failing.set(failing);
        }

        @Override
        public String name() {
            return "flaky";
        }

        @Override
        public Decision allow(String key, Limit limit) {
            calls.incrementAndGet();
            if (failing.get()) {
                throw new LimiterUnavailableException("redis: connection refused");
            }
            return Decision.allow(limit.capacity(), 1, limit.period());
        }
    }

    private static Decision allow(ResilientLimiter r, String key) {
        return allow(r, key, LIM);
    }

    private static Decision allow(ResilientLimiter r, String key, Limit lim) {
        try {
            return r.allow(key, lim);
        } catch (ExerciseNotImplementedException e) {
            return Assumptions.abort(e.getMessage());
        }
        // Any other exception fails the test: the resilience layer must never surface backend errors.
    }

    @Test
    void primaryIsUsedWhenHealthy() {
        Flaky p = new Flaky(false);
        ResilientLimiter r = new ResilientLimiter(p, ResilienceOptions.of(FailMode.CLOSED));
        assertThat(allow(r, "k").allowed()).isTrue();
        assertThat(p.calls.get()).isEqualTo(1);
    }

    @Test
    void failOpenAllows() {
        Flaky p = new Flaky(true);
        AtomicInteger fallbacks = new AtomicInteger();
        ResilientLimiter r = new ResilientLimiter(p, ResilienceOptions.of(FailMode.OPEN)
                .withOnFallback((m, a) -> fallbacks.incrementAndGet()));
        for (int i = 0; i < 3; i++) {
            assertThat(allow(r, "k").allowed()).as("fail-open must allow").isTrue();
        }
        assertThat(fallbacks.get()).as("onFallback invocations").isEqualTo(3);
    }

    @Test
    void failClosedDenies() {
        ResilientLimiter r = new ResilientLimiter(new Flaky(true), ResilienceOptions.of(FailMode.CLOSED));
        Decision d = allow(r, "k");
        assertThat(d.allowed()).as("fail-closed must deny").isFalse();
        assertThat(d.retryAfter()).as("a fail-closed denial should still tell the client when to retry").isPositive();
    }

    @Test
    void localFallbackUsesScaledLimit() {
        MutableClock clock = new MutableClock(START);
        ResilientLimiter r = new ResilientLimiter(new Flaky(true), ResilienceOptions.of(FailMode.LOCAL)
                .withFallback(new FixedWindowLimiter(clock)).withLocalShare(0.5).withClock(clock));
        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            if (allow(r, "k").allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).as("rate 10 * share 0.5").isEqualTo(5);
    }

    @Test
    void localShareNeverRoundsToZero() {
        MutableClock clock = new MutableClock(START);
        ResilientLimiter r = new ResilientLimiter(new Flaky(true), ResilienceOptions.of(FailMode.LOCAL)
                .withFallback(new FixedWindowLimiter(clock)).withLocalShare(0.1).withClock(clock));
        assertThat(allow(r, "k", Limit.of(1, Duration.ofSeconds(1))).allowed())
                .as("rate 1 * share 0.1 must still permit at least 1 request").isTrue();
    }

    @Test
    void breakerStopsCallingPrimary() {
        Flaky p = new Flaky(true);
        MutableClock clock = new MutableClock(START);
        ResilientLimiter r = new ResilientLimiter(p, ResilienceOptions.of(FailMode.OPEN)
                .withThreshold(3).withCooldown(Duration.ofSeconds(10)).withClock(clock));
        for (int i = 0; i < 3; i++) {
            allow(r, "k");
        }
        assertThat(r.state()).as("after 3 consecutive failures").isEqualTo("open");
        int before = p.calls.get();
        for (int i = 0; i < 100; i++) {
            allow(r, "k");
        }
        assertThat(p.calls.get()).as("primary calls while the breaker is open").isEqualTo(before);
    }

    @Test
    void breakerRecoversAfterCooldown() {
        Flaky p = new Flaky(true);
        MutableClock clock = new MutableClock(START);
        ResilientLimiter r = new ResilientLimiter(p, ResilienceOptions.of(FailMode.OPEN)
                .withThreshold(2).withCooldown(Duration.ofSeconds(10)).withClock(clock));
        allow(r, "k");
        allow(r, "k");
        assertThat(r.state()).isEqualTo("open");

        // Still open during cooldown even though the primary has recovered.
        p.failing.set(false);
        clock.advance(Duration.ofSeconds(5));
        int before = p.calls.get();
        allow(r, "k");
        assertThat(p.calls.get()).as("primary must not be called before cooldown elapses").isEqualTo(before);

        // After cooldown one probe reaches the primary; success closes the breaker.
        clock.advance(Duration.ofSeconds(6));
        allow(r, "k");
        assertThat(p.calls.get()).as("exactly one probe after cooldown").isEqualTo(before + 1);
        assertThat(r.state()).as("after a successful probe").isEqualTo("closed");
        allow(r, "k");
        assertThat(p.calls.get()).as("primary back in use").isEqualTo(before + 2);
    }

    @Test
    void failedProbeReopensBreaker() {
        Flaky p = new Flaky(true);
        MutableClock clock = new MutableClock(START);
        ResilientLimiter r = new ResilientLimiter(p, ResilienceOptions.of(FailMode.OPEN)
                .withThreshold(1).withCooldown(Duration.ofSeconds(10)).withClock(clock));
        allow(r, "k");
        clock.advance(Duration.ofSeconds(11));
        int before = p.calls.get();
        allow(r, "k"); // probe, fails
        assertThat(p.calls.get()).isEqualTo(before + 1);
        assertThat(r.state()).isEqualTo("open");
        allow(r, "k");
        assertThat(p.calls.get()).as("after a failed probe the breaker waits a full cooldown again").isEqualTo(before + 1);
    }

    @Test
    void safeUnderConcurrency() throws Exception {
        Flaky p = new Flaky(true);
        MutableClock clock = new MutableClock(START);
        ResilientLimiter r = new ResilientLimiter(p, ResilienceOptions.of(FailMode.LOCAL)
                .withFallback(new FixedWindowLimiter(clock)).withThreshold(3).withCooldown(Duration.ofSeconds(1)).withClock(clock));
        allow(r, "probe");

        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            for (int t = 0; t < 16; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 200; i++) {
                        r.allow("k", LIM);
                        if (i % 50 == 0) {
                            p.failing.set(i % 100 == 0);
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(); // any exception (including races surfacing as NPE/ISE) fails the test
            }
        }
    }
}
