package dev.parkerharrelson.ratelimiter.core.contract;

import dev.parkerharrelson.ratelimiter.core.Decision;
import dev.parkerharrelson.ratelimiter.core.ExerciseNotImplementedException;
import dev.parkerharrelson.ratelimiter.core.Limit;
import dev.parkerharrelson.ratelimiter.core.Limiter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract every {@link Limiter} must satisfy, regardless of algorithm or backend. Extend the
 * algorithm-family subclass ({@link FixedWindowContract}, {@link TokenBucketContract}, ...) and
 * implement {@link #newFixture()}.
 *
 * <p>This suite is the "grader" for the curriculum: an exercise is complete when its contract
 * tests pass. Stubs that throw {@link ExerciseNotImplementedException} are reported as skipped.
 */
public abstract class LimiterContract {

    protected Limiter limiter;
    protected TestClock clock;

    /** A fresh, empty limiter. Called before every test so no state leaks between tests. */
    protected abstract Fixture newFixture();

    /**
     * The period used for every limit in the suite. Fake clocks can use anything (10s reads
     * nicely). Real clocks should use something small like 1s so the suite finishes quickly;
     * steps are fractions of the unit.
     */
    protected Duration unit() {
        return Duration.ofSeconds(10);
    }

    /** Relaxes assertions that depend on time <em>not</em> having advanced, which sleeping cannot guarantee. */
    protected boolean realClock() {
        return false;
    }

    protected final Duration epsilon() {
        return unit().dividedBy(20);
    }

    @BeforeEach
    void setUp() {
        Fixture f = newFixture();
        limiter = f.limiter();
        clock = f.clock();
    }

    // ---------------------------------------------------------------- helpers

    /** Calls allow and converts "not implemented" into a skipped test. */
    protected final Decision allow(String key, Limit limit) {
        try {
            return limiter.allow(key, limit);
        } catch (ExerciseNotImplementedException e) {
            return Assumptions.abort(e.getMessage());
        }
    }

    /** Sends requests until one is denied or {@code max} is reached; returns how many were allowed. */
    protected final int drain(String key, Limit limit, int max) {
        int n = 0;
        for (int i = 0; i < max; i++) {
            if (!allow(key, limit).allowed()) {
                return n;
            }
            n++;
        }
        return n;
    }

    /**
     * Advances to just past the next unit-aligned boundary (2.5% to ~5% into the new window) so
     * tests that depend on window position are deterministic. Windows are absolute
     * ({@code index = floor(now / period)}) in every algorithm in this project.
     */
    protected final void alignToWindowStart() {
        long periodNanos = unit().toNanos();
        Instant now = clock.now();
        long nowNanos = now.getEpochSecond() * 1_000_000_000L + now.getNano();
        long next = (Math.floorDiv(nowNanos, periodNanos) + 1) * periodNanos;
        clock.advance(Duration.ofNanos(next - nowNanos).plus(epsilon().dividedBy(2)));
    }

    // ---------------------------------------------------------------- common contract

    @Test
    void allowsUpToCapacityThenDenies() {
        Limit lim = Limit.of(5, unit());
        for (int i = 1; i <= 5; i++) {
            Decision d = allow("alice", lim);
            assertThat(d.allowed()).as("request %d of 5", i).isTrue();
            assertThat(d.limit()).as("limit header").isEqualTo(5);
            assertThat(d.remaining()).as("remaining after request %d", i).isEqualTo(5 - i);
            assertThat(d.retryAfter()).as("retryAfter on an allowed decision").isZero();
        }
        Decision denied = allow("alice", lim);
        assertThat(denied.allowed()).as("6th request within the period").isFalse();
        assertThat(denied.remaining()).isZero();
        assertThat(denied.retryAfter()).as("retryAfter when denied").isPositive().isLessThanOrEqualTo(unit());
        assertThat(denied.resetAfter()).as("resetAfter when denied").isPositive();
    }

    @Test
    void keysAreIndependent() {
        Limit lim = Limit.of(3, unit());
        assertThat(drain("alice", lim, 10)).isEqualTo(3);
        Decision bob = allow("bob", lim);
        assertThat(bob.allowed()).as("bob's first request after alice was exhausted").isTrue();
        assertThat(bob.remaining()).isEqualTo(2);
    }

    @Test
    void concurrentCallersNeverExceedCapacity() throws Exception {
        int capacity = 50;
        int threads = 32;
        int perThread = 20; // 640 attempts against a capacity of 50
        // A long period keeps refill negligible even on a real clock.
        Limit lim = Limit.of(capacity, unit().multipliedBy(10));

        allow("probe", lim); // a stub skips here instead of failing from 32 threads

        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            try {
                                if (limiter.allow("shared", lim).allowed()) {
                                    allowed.incrementAndGet();
                                }
                            } catch (RuntimeException e) {
                                errors.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }
        assertThat(errors.get()).as("allow calls that threw").isZero();
        if (realClock()) {
            assertThat(allowed.get()).as("allowed under contention (real clock, +2 tolerance)")
                    .isBetween(capacity, capacity + 2);
        } else {
            assertThat(allowed.get()).as("allowed under contention; anything else is a race").isEqualTo(capacity);
        }
    }

    @Test
    void resetAfterIsHonest() {
        Limit lim = Limit.of(4, unit());
        assertThat(drain("alice", lim, 10)).isEqualTo(4);
        Decision denied = allow("alice", lim);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.resetAfter()).isPositive();

        // resetAfter promises the *entire* budget is back. Every algorithm defines it differently
        // (window end, time to refill to burst, time for the log to empty, two boundaries for the
        // counter approximation) but the promise is the same.
        clock.advance(denied.resetAfter().plus(epsilon()));
        assertThat(drain("alice", lim, 10)).as("after resetAfter %s", denied.resetAfter()).isEqualTo(4);
    }

    @Test
    void retryAfterIsHonest() {
        Limit lim = Limit.of(3, unit());
        drain("alice", lim, 10);
        Decision denied = allow("alice", lim);
        assertThat(denied.allowed()).isFalse();

        clock.advance(denied.retryAfter().plus(epsilon()));
        assertThat(allow("alice", lim).allowed())
                .as("after waiting the advertised retryAfter of %s", denied.retryAfter()).isTrue();
    }
}
