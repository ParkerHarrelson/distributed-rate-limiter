package dev.parkerharrelson.ratelimiter.core.contract;

import dev.parkerharrelson.ratelimiter.core.clock.MutableClock;

import java.time.Duration;
import java.time.Instant;

/** Adapters between the production clock types and the suite's {@link TestClock}. */
public final class TestClocks {

    /**
     * An arbitrary fixed instant. Choosing one that is not aligned to a 10s boundary matters: it
     * means tests begin mid-window, like real traffic.
     */
    public static final Instant START = Instant.ofEpochSecond(1_700_000_003L, 250_000_000);

    private TestClocks() {
    }

    public static TestClock of(MutableClock clock) {
        return new TestClock() {
            @Override
            public Instant now() {
                return clock.instant();
            }

            @Override
            public void advance(Duration d) {
                clock.advance(d);
            }
        };
    }
}
