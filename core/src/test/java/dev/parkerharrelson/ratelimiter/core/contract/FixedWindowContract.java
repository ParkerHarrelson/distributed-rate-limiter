package dev.parkerharrelson.ratelimiter.core.contract;

import dev.parkerharrelson.ratelimiter.core.Limit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Fixed window semantics: counts in aligned, non-overlapping windows. */
public abstract class FixedWindowContract extends LimiterContract {

    /**
     * The fixed window's well-known weakness: a client can send 2x the limit in an instant by
     * straddling a boundary. This test <em>documents</em> that behaviour so the comparison against
     * sliding windows is measurable.
     */
    @Test
    void boundaryBurstIsAllowed() {
        Limit lim = Limit.of(10, unit());
        alignToWindowStart();
        clock.advance(unit().minus(epsilon().multipliedBy(2))); // just before the boundary

        assertThat(drain("alice", lim, 20)).as("just before boundary").isEqualTo(10);
        clock.advance(epsilon().multipliedBy(3)); // cross it
        assertThat(drain("alice", lim, 20))
                .as("just after boundary (fixed window permits a 2x burst)").isEqualTo(10);
    }
}
