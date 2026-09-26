package dev.parkerharrelson.ratelimiter.core.redis;

import dev.parkerharrelson.ratelimiter.core.contract.Fixture;
import dev.parkerharrelson.ratelimiter.core.contract.TestClock;
import dev.parkerharrelson.ratelimiter.core.contract.TokenBucketContract;

import java.time.Duration;

class RedisTokenBucketLimiterTest extends TokenBucketContract {

    @Override
    protected Fixture newFixture() {
        return new Fixture(new RedisTokenBucketLimiter(RedisTestSupport.connection(), RedisTestSupport.freshOptions()),
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
