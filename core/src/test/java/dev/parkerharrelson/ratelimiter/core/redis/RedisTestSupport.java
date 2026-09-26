package dev.parkerharrelson.ratelimiter.core.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides one Redis for all integration tests in this package.
 *
 * <p>Resolution order: {@code REDIS_ADDR} if set (reuse the docker-compose stack), otherwise a
 * Testcontainers {@code redis:7-alpine}. If neither is possible, or the build was run with
 * {@code -PskipIntegration}, the tests are skipped rather than failed.
 */
final class RedisTestSupport {

    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private static volatile String address;
    private static volatile RedisClient client;
    private static volatile StatefulRedisConnection<String, String> connection;

    private RedisTestSupport() {
    }

    static synchronized StatefulRedisConnection<String, String> connection() {
        Assumptions.assumeFalse(Boolean.getBoolean("ratelimiter.skipIntegration"), "-PskipIntegration set");
        if (connection == null) {
            String env = System.getenv("REDIS_ADDR");
            if (env != null && !env.isBlank()) {
                address = env;
            } else {
                Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                        "Docker not available and REDIS_ADDR not set; skipping Redis integration tests");
                @SuppressWarnings("resource")
                GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
                redis.start();
                Runtime.getRuntime().addShutdownHook(new Thread(redis::stop));
                address = redis.getHost() + ":" + redis.getMappedPort(6379);
            }
            client = RedisConnections.client(address, TIMEOUT);
            connection = RedisConnections.connect(client, TIMEOUT);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                connection.close();
                client.shutdown();
            }));
        }
        return connection;
    }

    /** A key prefix no other test shares. */
    static RedisLimiterOptions freshOptions() {
        return RedisLimiterOptions.DEFAULT
                .withKeyPrefix("test:" + System.nanoTime() + ":" + COUNTER.incrementAndGet() + ":")
                .withTimeout(TIMEOUT);
    }
}
