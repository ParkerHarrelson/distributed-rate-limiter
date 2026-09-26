package dev.parkerharrelson.ratelimiter.core.memory;

import dev.parkerharrelson.ratelimiter.core.clock.MutableClock;
import dev.parkerharrelson.ratelimiter.core.contract.Fixture;
import dev.parkerharrelson.ratelimiter.core.contract.SlidingCounterContract;
import dev.parkerharrelson.ratelimiter.core.contract.TestClocks;

class SlidingCounterLimiterTest extends SlidingCounterContract {

    @Override
    protected Fixture newFixture() {
        MutableClock clock = new MutableClock(TestClocks.START);
        return new Fixture(new SlidingCounterLimiter(clock), TestClocks.of(clock));
    }
}
