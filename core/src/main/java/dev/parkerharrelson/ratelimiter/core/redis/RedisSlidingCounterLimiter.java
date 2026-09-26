package dev.parkerharrelson.ratelimiter.core.redis;

import dev.parkerharrelson.ratelimiter.core.Decision;
import dev.parkerharrelson.ratelimiter.core.Limit;
import dev.parkerharrelson.ratelimiter.core.Limiter;
import io.lettuce.core.api.StatefulRedisConnection;

import java.util.function.Supplier;

/**
 * Sliding window counter in Redis - EXERCISE 4. The Java side is complete; the algorithm lives
 * in {@code lua/sliding_counter.lua}.
 */
public final class RedisSlidingCounterLimiter implements Limiter {

    private static final RedisScript SCRIPT = new RedisScript("sliding_counter.lua");

    private final Supplier<StatefulRedisConnection<String, String>> connection;
    private final RedisLimiterOptions options;

    public RedisSlidingCounterLimiter(StatefulRedisConnection<String, String> connection, RedisLimiterOptions options) {
        this(() -> connection, options);
    }

    /** Takes a supplier so the connection can be established lazily; see {@link RedisConnections#lazy}. */
    public RedisSlidingCounterLimiter(Supplier<StatefulRedisConnection<String, String>> connection, RedisLimiterOptions options) {
        this.connection = connection;
        this.options = options;
    }

    @Override
    public String name() {
        return "sliding_counter";
    }

    @Override
    public Decision allow(String key, Limit limit) {
        return SCRIPT.run(connection.get().sync(), options.keyPrefix() + key,
                limit.rate(), limit.period().toMillis());
    }
}
