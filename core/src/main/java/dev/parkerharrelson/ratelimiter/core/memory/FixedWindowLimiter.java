package dev.parkerharrelson.ratelimiter.core.memory;

import dev.parkerharrelson.ratelimiter.core.Decision;
import dev.parkerharrelson.ratelimiter.core.Limit;
import dev.parkerharrelson.ratelimiter.core.Limiter;
import dev.parkerharrelson.ratelimiter.core.clock.Instants;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed window counter, fully implemented as the worked example for the curriculum.
 *
 * <p>Time is divided into aligned windows of {@code limit.period()}; at most {@code limit.rate()}
 * requests are allowed per window. It is the cheapest algorithm to run (one integer per key) and
 * the least accurate: a client can send 2x the limit in a short span by straddling a boundary.
 *
 * <p>Window alignment is absolute, {@code index = floor(now / period)}, so every process and
 * every key agree on where boundaries fall. That is what makes the same idea trivially portable
 * to Redis.
 *
 * <p>Concurrency: a {@link ConcurrentHashMap} finds the per-key state without a global lock;
 * the state object itself is {@code synchronized} so a check-and-increment on one key is atomic.
 * Two requests for different keys never contend. This is the pattern to copy for the exercises;
 * the curriculum asks you to compare it against {@code ConcurrentHashMap.compute} with immutable
 * state and against a CAS loop on an {@code AtomicReference}.
 */
public final class FixedWindowLimiter implements Limiter, Sweepable {

    private final InstantSource clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowLimiter(InstantSource clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return "fixed_window";
    }

    /** Burst is ignored: the window itself is the only bound on burstiness. */
    @Override
    public Decision allow(String key, Limit limit) {
        Instant now = clock.instant();
        long periodNanos = limit.period().toNanos();
        long index = Math.floorDiv(Instants.epochNanos(now), periodNanos);
        Instant windowEnd = Instants.ofEpochNanos((index + 1) * periodNanos);
        Duration reset = Duration.between(now, windowEnd);

        Window window = windows.computeIfAbsent(key, k -> new Window());
        return window.tryConsume(index, windowEnd, limit.rate(), reset);
    }

    /**
     * Drops keys whose window has already ended. Safe to run concurrently with {@link #allow}:
     * an expired window would be reset by the next request anyway. (Question for Exercise 0:
     * there is a narrow race here that can over-admit by one. Find it.)
     */
    @Override
    public int sweep() {
        Instant now = clock.instant();
        int before = windows.size();
        windows.values().removeIf(w -> w.isExpired(now));
        return before - windows.size();
    }

    @Override
    public int size() {
        return windows.size();
    }

    /** Per-key state. All access goes through synchronized methods. */
    private static final class Window {
        private long index = Long.MIN_VALUE;
        private int count;
        private Instant expiresAt = Instant.EPOCH;

        synchronized Decision tryConsume(long currentIndex, Instant windowEnd, int rate, Duration reset) {
            if (index != currentIndex) {
                // First request in a new window: start counting from zero.
                index = currentIndex;
                count = 0;
                expiresAt = windowEnd;
            }
            if (count >= rate) {
                return Decision.deny(rate, reset, reset);
            }
            count++;
            return Decision.allow(rate, rate - count, reset);
        }

        synchronized boolean isExpired(Instant now) {
            return !expiresAt.isAfter(now);
        }
    }
}
