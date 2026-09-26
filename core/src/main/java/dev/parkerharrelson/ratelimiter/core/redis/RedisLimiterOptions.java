package dev.parkerharrelson.ratelimiter.core.redis;

import java.time.Duration;

/**
 * Settings shared by every Redis-backed limiter.
 *
 * @param keyPrefix namespaces all keys, e.g. {@code rl:}
 * @param timeout   bounds each {@code allow} call. A rate limiter that is slower than the request
 *                  it protects is worse than no rate limiter; keep this small.
 */
public record RedisLimiterOptions(String keyPrefix, Duration timeout) {

    public static final RedisLimiterOptions DEFAULT = new RedisLimiterOptions("rl:", Duration.ofMillis(50));

    public RedisLimiterOptions withKeyPrefix(String prefix) {
        return new RedisLimiterOptions(prefix, timeout);
    }

    public RedisLimiterOptions withTimeout(Duration t) {
        return new RedisLimiterOptions(keyPrefix, t);
    }
}
