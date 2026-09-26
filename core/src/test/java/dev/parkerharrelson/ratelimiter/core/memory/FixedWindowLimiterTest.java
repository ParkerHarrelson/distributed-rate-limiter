package dev.parkerharrelson.ratelimiter.core.memory;

import dev.parkerharrelson.ratelimiter.core.Limit;
import dev.parkerharrelson.ratelimiter.core.clock.MutableClock;
import dev.parkerharrelson.ratelimiter.core.contract.Fixture;
import dev.parkerharrelson.ratelimiter.core.contract.FixedWindowContract;
import dev.parkerharrelson.ratelimiter.core.contract.TestClocks;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowLimiterTest extends FixedWindowContract {

    @Override
    protected Fixture newFixture() {
        MutableClock clock = new MutableClock(TestClocks.START);
        return new Fixture(new FixedWindowLimiter(clock), TestClocks.of(clock));
    }

    @Test
    void sweepRemovesOnlyExpiredWindows() {
        MutableClock clock = new MutableClock(TestClocks.START);
        FixedWindowLimiter fw = new FixedWindowLimiter(clock);
        Limit lim = Limit.of(5, Duration.ofSeconds(1));
        for (String k : new String[]{"a", "b", "c"}) {
            fw.allow(k, lim);
        }
        assertThat(fw.size()).isEqualTo(3);
        assertThat(fw.sweep()).as("nothing expired yet").isZero();
        clock.advance(Duration.ofSeconds(2));
        assertThat(fw.sweep()).isEqualTo(3);
        assertThat(fw.size()).isZero();
    }
}
