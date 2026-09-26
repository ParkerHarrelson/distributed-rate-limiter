package dev.parkerharrelson.ratelimiter.core.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;

import dev.parkerharrelson.ratelimiter.core.LimiterUnavailableException;
import io.lettuce.core.RedisException;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Builds a Lettuce connection tuned for rate limiting: short command timeout, no command
 * queueing while disconnected (fail fast instead of buffering a backlog that floods Redis when
 * it returns), and automatic reconnect.
 *
 * <p>One {@link StatefulRedisConnection} is thread-safe and multiplexes every caller over a
 * single socket; that is the intended usage, not a limitation.
 */
public final class RedisConnections {

    private RedisConnections() {
    }

    public static RedisClient client(String host, int port, Duration commandTimeout) {
        RedisURI uri = RedisURI.Builder.redis(host, port).withTimeout(commandTimeout).build();
        RedisClient client = RedisClient.create(uri);
        client.setOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .timeoutOptions(TimeoutOptions.enabled(commandTimeout))
                .build());
        return client;
    }

    /** Parses {@code host:port} (port optional, default 6379). */
    public static RedisClient client(String address, Duration commandTimeout) {
        int idx = address.lastIndexOf(':');
        String host = idx < 0 ? address : address.substring(0, idx);
        int port = idx < 0 ? 6379 : Integer.parseInt(address.substring(idx + 1));
        return client(host, port, commandTimeout);
    }

    public static StatefulRedisConnection<String, String> connect(RedisClient client, Duration commandTimeout) {
        StatefulRedisConnection<String, String> connection = client.connect();
        connection.setTimeout(commandTimeout);
        return connection;
    }

    /**
     * A supplier that connects on first use and retries on every call until it succeeds, so a
     * replica can start before Redis is reachable. Once connected, Lettuce's auto-reconnect
     * takes over. Failed attempts surface as {@link LimiterUnavailableException}.
     */
    public static Supplier<StatefulRedisConnection<String, String>> lazy(RedisClient client, Duration commandTimeout) {
        return new Supplier<>() {
            private volatile StatefulRedisConnection<String, String> connection;

            @Override
            public StatefulRedisConnection<String, String> get() {
                StatefulRedisConnection<String, String> c = connection;
                if (c != null) {
                    return c;
                }
                synchronized (this) {
                    if (connection == null) {
                        try {
                            connection = connect(client, commandTimeout);
                        } catch (RedisException e) {
                            throw new LimiterUnavailableException("redis connect failed: " + e.getMessage(), e);
                        }
                    }
                    return connection;
                }
            }
        };
    }
}
