package dev.parkerharrelson.ratelimiter.core.redis;

import dev.parkerharrelson.ratelimiter.core.Decision;
import dev.parkerharrelson.ratelimiter.core.Limit;
import dev.parkerharrelson.ratelimiter.core.Limiter;
import io.lettuce.core.api.StatefulRedisConnection;

import java.util.function.Supplier;

/**
 * Redis-backed twin of the in-memory fixed window. The algorithm is in
 * {@code lua/fixed_window.lua}; this class only marshals arguments.
 *
 * <p>Why Lua: a rate-limit check is read-modify-write. Redis executes a script as one indivisible
 * unit on its single command thread, so two replicas can never both observe "1 left" and both
 * take it. The alternatives (WATCH/MULTI optimistic transactions, client-side CAS loops) work
 * but cost round trips under contention, which is exactly when a rate limiter is busiest.
 *
 * <p>Why Redis {@code TIME}: scripts read the clock inside Redis rather than trusting an
 * argument. Replica clocks drift; with client time a replica 2s ahead would open new windows
 * early and let a client double-dip. Redis >= 5 replicates script effects, so reading TIME
 * inside a script is safe.
 */
public final class RedisFixedWindowLimiter implements Limiter {

    private static final RedisScript SCRIPT = new RedisScript("fixed_window.lua");

    private final Supplier<StatefulRedisConnection<String, String>> connection;
    private final RedisLimiterOptions options;

    public RedisFixedWindowLimiter(StatefulRedisConnection<String, String> connection, RedisLimiterOptions options) {
        this(() -> connection, options);
    }

    /** Takes a supplier so the connection can be established lazily; see {@link RedisConnections#lazy}. */
    public RedisFixedWindowLimiter(Supplier<StatefulRedisConnection<String, String>> connection, RedisLimiterOptions options) {
        this.connection = connection;
        this.options = options;
    }

    @Override
    public String name() {
        return "fixed_window";
    }

    @Override
    public Decision allow(String key, Limit limit) {
        return SCRIPT.run(connection.get().sync(), options.keyPrefix() + key,
                limit.rate(), limit.period().toMillis());
    }
}
