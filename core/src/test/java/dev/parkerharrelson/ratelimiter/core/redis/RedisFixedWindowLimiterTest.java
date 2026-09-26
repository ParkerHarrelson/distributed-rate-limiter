package dev.parkerharrelson.ratelimiter.core.redis;

import dev.parkerharrelson.ratelimiter.core.Limit;
import dev.parkerharrelson.ratelimiter.core.LimiterUnavailableException;
import dev.parkerharrelson.ratelimiter.core.contract.Fixture;
import dev.parkerharrelson.ratelimiter.core.contract.FixedWindowContract;
import dev.parkerharrelson.ratelimiter.core.contract.TestClock;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests: real Redis, real clock, so the contract runs with a 1s unit and actually
 * sleeps. Each test gets a unique key prefix so tests never see each other's state.
 */
class RedisFixedWindowLimiterTest extends FixedWindowContract {

    private RedisLimiterOptions options;

    @Override
    protected Fixture newFixture() {
        StatefulRedisConnection<String, String> conn = RedisTestSupport.connection();
        options = RedisTestSupport.freshOptions();
        return new Fixture(new RedisFixedWindowLimiter(conn, options), new TestClock.SleepClock());
    }

    @Override
    protected Duration unit() {
        return Duration.ofSeconds(1);
    }

    @Override
    protected boolean realClock() {
        return true;
    }

    /** The housekeeping the contract cannot see: keys must expire on their own. */
    @Test
    void setsTtlOnTheKey() {
        Limit lim = Limit.of(5, Duration.ofSeconds(10));
        allow("alice", lim);
        long ttlMs = RedisTestSupport.connection().sync().pttl(options.keyPrefix() + "alice");
        assertThat(ttlMs).isPositive().isLessThanOrEqualTo(10_000);
    }

    /** A slow or unreachable Redis must surface as an error quickly, not hang the request. */
    @Test
    void unreachableRedisFailsFast() {
        // TEST-NET-1 (192.0.2.0/24) is guaranteed unroutable: connections hang, they are not refused.
        Duration timeout = Duration.ofMillis(100);
        RedisClient dead = RedisConnections.client("192.0.2.1", 6379, timeout);
        try {
            var lazy = RedisConnections.lazy(dead, timeout);
            var fw = new RedisFixedWindowLimiter(lazy, RedisLimiterOptions.DEFAULT.withTimeout(timeout));
            long start = System.nanoTime();
            assertThatThrownBy(() -> fw.allow("alice", Limit.of(1, Duration.ofSeconds(1))))
                    .isInstanceOf(LimiterUnavailableException.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs).as("time to fail against a black-hole address").isLessThan(2_000);
        } finally {
            dead.shutdown(Duration.ZERO, Duration.ZERO);
        }
    }
}
