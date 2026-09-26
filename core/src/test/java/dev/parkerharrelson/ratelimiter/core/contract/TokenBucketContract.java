package dev.parkerharrelson.ratelimiter.core.contract;

import dev.parkerharrelson.ratelimiter.core.Decision;
import dev.parkerharrelson.ratelimiter.core.Limit;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Token bucket semantics: continuous refill, bursts up to capacity. */
public abstract class TokenBucketContract extends LimiterContract {

    @Test
    void refillsContinuously() {
        // 10 tokens per unit -> one token every unit/10.
        Limit lim = new Limit(10, unit(), 10);
        Duration tick = unit().dividedBy(10);

        assertThat(drain("alice", lim, 20)).as("initial burst").isEqualTo(10);

        clock.advance(tick.plus(epsilon().dividedBy(2)));
        assertThat(allow("alice", lim).allowed()).as("one tick after exhaustion yields one token").isTrue();
        assertThat(allow("alice", lim).allowed()).as("second request within the same tick").isFalse();

        if (!realClock()) {
            clock.advance(tick.dividedBy(2));
            assertThat(allow("alice", lim).allowed()).as("half a tick is not a whole token").isFalse();
            clock.advance(tick.dividedBy(2));
            assertThat(allow("alice", lim).allowed()).as("two half ticks accumulate to one token").isTrue();
        }

        clock.advance(tick.multipliedBy(3).plus(epsilon().dividedBy(2)));
        assertThat(drain("alice", lim, 20)).as("after 3 ticks").isEqualTo(3);
    }

    @Test
    void capacityIsNotExceededAfterIdle() {
        Limit lim = new Limit(10, unit(), 10);
        drain("alice", lim, 20);
        clock.advance(unit().multipliedBy(100)); // idle for a long time
        assertThat(drain("alice", lim, 50)).as("after long idle (bucket must cap at burst)").isEqualTo(10);
    }

    @Test
    void burstSmallerThanRate() {
        // Sustained 100/unit but never more than 5 at once.
        Limit lim = new Limit(100, unit(), 5);
        assertThat(drain("alice", lim, 50)).as("burst").isEqualTo(5);
        Decision denied = allow("alice", lim);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.limit()).as("limit header is burst, not rate").isEqualTo(5);

        clock.advance(unit().dividedBy(100).plus(epsilon().dividedBy(4))); // one refill interval
        assertThat(allow("alice", lim).allowed()).as("after one refill interval").isTrue();
    }
}
