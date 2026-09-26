package dev.parkerharrelson.ratelimiter.core.memory;

import dev.parkerharrelson.ratelimiter.core.clock.MutableClock;
import dev.parkerharrelson.ratelimiter.core.contract.Fixture;
import dev.parkerharrelson.ratelimiter.core.contract.TestClocks;
import dev.parkerharrelson.ratelimiter.core.contract.TokenBucketContract;

class TokenBucketLimiterTest extends TokenBucketContract {

    @Override
    protected Fixture newFixture() {
        MutableClock clock = new MutableClock(TestClocks.START);
        return new Fixture(new TokenBucketLimiter(clock), TestClocks.of(clock));
    }
}
