package dev.parkerharrelson.ratelimiter.core.memory;

import dev.parkerharrelson.ratelimiter.core.clock.MutableClock;
import dev.parkerharrelson.ratelimiter.core.contract.Fixture;
import dev.parkerharrelson.ratelimiter.core.contract.SlidingLogContract;
import dev.parkerharrelson.ratelimiter.core.contract.TestClocks;

class SlidingLogLimiterTest extends SlidingLogContract {

    @Override
    protected Fixture newFixture() {
        MutableClock clock = new MutableClock(TestClocks.START);
        return new Fixture(new SlidingLogLimiter(clock), TestClocks.of(clock));
    }
}
