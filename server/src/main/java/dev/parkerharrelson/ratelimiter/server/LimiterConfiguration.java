package dev.parkerharrelson.ratelimiter.server;

import dev.parkerharrelson.ratelimiter.core.Limiter;
import dev.parkerharrelson.ratelimiter.core.clock.SkewedClock;
import dev.parkerharrelson.ratelimiter.core.memory.FixedWindowLimiter;
import dev.parkerharrelson.ratelimiter.core.memory.SlidingCounterLimiter;
import dev.parkerharrelson.ratelimiter.core.memory.SlidingLogLimiter;
import dev.parkerharrelson.ratelimiter.core.memory.Sweepable;
import dev.parkerharrelson.ratelimiter.core.memory.TokenBucketLimiter;
import dev.parkerharrelson.ratelimiter.core.policy.Policy;
import dev.parkerharrelson.ratelimiter.core.policy.PolicyFile;
import dev.parkerharrelson.ratelimiter.core.policy.PolicyLoader;
import dev.parkerharrelson.ratelimiter.core.redis.RedisConnections;
import dev.parkerharrelson.ratelimiter.core.redis.RedisFixedWindowLimiter;
import dev.parkerharrelson.ratelimiter.core.redis.RedisLimiterOptions;
import dev.parkerharrelson.ratelimiter.core.redis.RedisSlidingCounterLimiter;
import dev.parkerharrelson.ratelimiter.core.redis.RedisTokenBucketLimiter;
import dev.parkerharrelson.ratelimiter.core.resilience.FailMode;
import dev.parkerharrelson.ratelimiter.core.resilience.ResilienceOptions;
import dev.parkerharrelson.ratelimiter.core.resilience.ResilientLimiter;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Wires the limiter stack: policy file -> algorithm -> optional resilience layer. */
@Configuration
public class LimiterConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LimiterConfiguration.class);

    @Bean
    PolicyFile policyFile(RateLimiterProperties props) {
        PolicyFile file = PolicyLoader.load(Path.of(props.limitsFile()));
        file.policy().rules().forEach(r -> log.info("rule name={} scope={} limit={} endpoints={} users={}",
                r.name(), r.scope().configName(), r.limit(), r.endpoints(), r.users()));
        return file;
    }

    @Bean
    Policy policy(PolicyFile file) {
        return file.policy();
    }

    @Bean
    RuntimeSettings runtimeSettings(RateLimiterProperties props, PolicyFile file) {
        String algorithm = firstNonBlank(props.algorithm(), file.algorithm(), "fixed_window");
        Optional<FailMode> failMode = FailMode.parse(firstNonBlank(props.failMode(), file.failMode(), ""));
        RuntimeSettings s = new RuntimeSettings(algorithm, failMode, props.backend(), props.instanceId());
        log.info("instance={} backend={} algorithm={} failMode={} redis={} timeout={}",
                s.instanceId(), s.backend(), s.algorithm(), s.failModeName(), props.redisAddr(), props.redisTimeout());
        return s;
    }

    /** A skewed clock only affects the memory backend; that asymmetry is the lesson. */
    @Bean
    InstantSource clock(RateLimiterProperties props) {
        if (!props.clockSkew().isZero()) {
            log.warn("running with a skewed clock: {}", props.clockSkew());
            return new SkewedClock(props.clockSkew());
        }
        return InstantSource.system();
    }

    /** Memory limiters that need periodic sweeping (the primary if BACKEND=memory, plus any fallback). */
    @Bean
    List<Sweepable> sweepables() {
        return new ArrayList<>();
    }

    @Bean(destroyMethod = "shutdown")
    RedisClient redisClient(RateLimiterProperties props) {
        return RedisConnections.client(props.redisAddr(), props.redisTimeout());
    }

    @Bean
    Limiter limiter(RateLimiterProperties props, RuntimeSettings settings, InstantSource clock,
                    RedisClient redisClient, List<Sweepable> sweepables, MeterRegistry registry) {
        Limiter primary;
        if ("memory".equals(settings.backend())) {
            primary = memoryLimiter(settings.algorithm(), clock, sweepables);
        } else {
            Supplier<StatefulRedisConnection<String, String>> conn = RedisConnections.lazy(redisClient, props.redisTimeout());
            RedisLimiterOptions options = RedisLimiterOptions.DEFAULT.withTimeout(props.redisTimeout());
            primary = switch (settings.algorithm()) {
                case "fixed_window" -> new RedisFixedWindowLimiter(conn, options);
                case "token_bucket" -> new RedisTokenBucketLimiter(conn, options);
                case "sliding_counter" -> new RedisSlidingCounterLimiter(conn, options);
                default -> throw new IllegalArgumentException("unknown algorithm '" + settings.algorithm()
                        + "' for redis backend (want fixed_window|token_bucket|sliding_counter)");
            };
        }

        Limiter limiter = primary;
        if (settings.failMode().isPresent()) {
            FailMode mode = settings.failMode().get();
            Limiter fallback = memoryLimiter(settings.algorithm(), clock, sweepables);
            limiter = new ResilientLimiter(primary, ResilienceOptions.of(mode)
                    .withFallback(fallback)
                    .withLocalShare(props.localShare())
                    .withClock(clock)
                    .withOnFallback((m, allowed) -> registry.counter("ratelimiter.fallback.decisions",
                            "mode", m.configName(), "result", allowed ? "allowed" : "denied").increment()));
        }

        Gauge.builder("ratelimiter.info", () -> 1)
                .description("Static configuration of this instance (always 1)")
                .tags("instance_id", settings.instanceId(), "algorithm", settings.algorithm(),
                        "backend", settings.backend(), "fail_mode", settings.failModeName())
                .register(registry);
        return limiter;
    }

    private static Limiter memoryLimiter(String algorithm, InstantSource clock, List<Sweepable> sweepables) {
        Limiter l = switch (algorithm) {
            case "token_bucket" -> new TokenBucketLimiter(clock);
            case "sliding_log" -> new SlidingLogLimiter(clock);
            case "sliding_counter" -> new SlidingCounterLimiter(clock);
            default -> new FixedWindowLimiter(clock);
        };
        if (l instanceof Sweepable s) {
            sweepables.add(s);
        }
        return l;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
