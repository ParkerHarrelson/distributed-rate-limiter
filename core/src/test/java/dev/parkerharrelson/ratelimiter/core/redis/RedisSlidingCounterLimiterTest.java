package dev.parkerharrelson.ratelimiter.core.redis;

import dev.parkerharrelson.ratelimiter.core.contract.Fixture;
import dev.parkerharrelson.ratelimiter.core.contract.SlidingCounterContract;
import dev.parkerharrelson.ratelimiter.core.contract.TestClock;

import java.time.Duration;

class RedisSlidingCounterLimiterTest extends SlidingCounterContract {

    @Override
    protected Fixture newFixture() {
        return new Fixture(new RedisSlidingCounterLimiter(RedisTestSupport.connection(), RedisTestSupport.freshOptions()),
                new TestClock.SleepClock());
    }

    @Override
    protected Duration unit() {
        return Duration.ofSeconds(1);
    }

    @Override
    protected boolean realClock() {
        return true;
    }
}
