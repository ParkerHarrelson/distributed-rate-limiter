package dev.parkerharrelson.ratelimiter.core.memory;

import dev.parkerharrelson.ratelimiter.core.Decision;
import dev.parkerharrelson.ratelimiter.core.ExerciseNotImplementedException;
import dev.parkerharrelson.ratelimiter.core.Limit;
import dev.parkerharrelson.ratelimiter.core.Limiter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.time.Duration;
import java.time.InstantSource;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Throughput of the in-memory algorithms on one hot key (maximum contention) and across 1024
 * keys (realistic). Run with {@code ./gradlew :core:jmh}. Algorithms that are still exercise
 * stubs fail fast in setup; remove them from the param list or implement them.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class LimiterBenchmark {

    private static final Limit LIMIT = Limit.of(1_000_000, Duration.ofSeconds(1));
    private static final int KEYS = 1024;

    @Param({"fixed_window", "token_bucket", "sliding_log", "sliding_counter"})
    public String algorithm;

    private Limiter limiter;
    private String[] keys;
    private final AtomicInteger next = new AtomicInteger();

    @Setup(Level.Trial)
    public void setUp() {
        InstantSource clock = InstantSource.system();
        limiter = switch (algorithm) {
            case "fixed_window" -> new FixedWindowLimiter(clock);
            case "token_bucket" -> new TokenBucketLimiter(clock);
            case "sliding_log" -> new SlidingLogLimiter(clock);
            case "sliding_counter" -> new SlidingCounterLimiter(clock);
            default -> throw new IllegalArgumentException(algorithm);
        };
        keys = new String[KEYS];
        for (int i = 0; i < KEYS; i++) {
            keys[i] = "user-" + i;
        }
        try {
            limiter.allow("warmup", LIMIT);
        } catch (ExerciseNotImplementedException e) {
            throw new IllegalStateException(algorithm + " is not implemented yet; drop it from @Param", e);
        }
    }

    @Benchmark
    public Decision hotKey() {
        return limiter.allow("hot", LIMIT);
    }

    @Benchmark
    public Decision manyKeys() {
        return limiter.allow(keys[next.getAndIncrement() & (KEYS - 1)], LIMIT);
    }
}
