package dev.parkerharrelson.ratelimiter.core.contract;

import dev.parkerharrelson.ratelimiter.core.Limit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sliding window counter semantics. The estimate for the trailing window is
 * {@code previous * (1 - elapsed) + current}; a request is allowed iff {@code estimate + 1 <= rate}.
 */
public abstract class SlidingCounterContract extends LimiterContract {

    @Test
    void noBoundaryBurst() {
        Limit lim = Limit.of(10, unit());
        alignToWindowStart();
        assertThat(drain("alice", lim, 20)).as("fresh key").isEqualTo(10);

        // Cross into the next window by a hair (we are < 10% into it):
        // previous weighs > 0.9 -> estimate > 9 -> estimate + 1 > 10 -> deny.
        clock.advance(unit());
        assertThat(drain("alice", lim, 20))
                .as("just after boundary (previous window still weighs >90%%)").isZero();
    }

    @Test
    void weightedApproximation() {
        Limit lim = Limit.of(10, unit());
        alignToWindowStart();
        assertThat(drain("alice", lim, 20)).as("fresh key").isEqualTo(10);

        // Just past the midpoint of the *next* window (50-60% in):
        // estimate = 10 * (0.4..0.5) + current; allowed iff estimate + 1 <= 10 -> 5.
        clock.advance(unit().plus(unit().dividedBy(2)));
        assertThat(drain("alice", lim, 20)).as("~50%% into the next window").isEqualTo(5);
    }
}
