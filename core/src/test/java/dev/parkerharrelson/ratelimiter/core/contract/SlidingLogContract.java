package dev.parkerharrelson.ratelimiter.core.contract;

import dev.parkerharrelson.ratelimiter.core.Limit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Sliding log semantics: exact trailing window, no boundary burst. */
public abstract class SlidingLogContract extends LimiterContract {

    @Test
    void noBoundaryBurst() {
        Limit lim = Limit.of(10, unit());

        // t=0: 5 requests. t=0.5: 5 more (now full).
        assertThat(drain("alice", lim, 5)).as("t=0").isEqualTo(5);
        clock.advance(unit().dividedBy(2));
        assertThat(drain("alice", lim, 5)).as("t=0.5").isEqualTo(5);
        assertThat(allow("alice", lim).allowed()).as("t=0.5 with 10 in window").isFalse();

        // t=1.0+eps: only the first 5 have aged out -> exactly 5 allowed.
        clock.advance(unit().dividedBy(2).plus(epsilon()));
        assertThat(drain("alice", lim, 20)).as("t=1.0 (no 2x boundary burst)").isEqualTo(5);

        // t=1.5+eps: the second batch ages out.
        clock.advance(unit().dividedBy(2));
        assertThat(drain("alice", lim, 20)).as("t=1.5").isEqualTo(5);
    }
}
