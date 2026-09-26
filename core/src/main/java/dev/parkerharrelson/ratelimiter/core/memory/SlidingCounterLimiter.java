package dev.parkerharrelson.ratelimiter.core.memory;

import dev.parkerharrelson.ratelimiter.core.Decision;
import dev.parkerharrelson.ratelimiter.core.ExerciseNotImplementedException;
import dev.parkerharrelson.ratelimiter.core.Limit;
import dev.parkerharrelson.ratelimiter.core.Limiter;

import java.time.InstantSource;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding window counter - EXERCISE 2b (see docs/curriculum.md).
 *
 * <p>The approximation popularised by Cloudflare: keep only two fixed-window counters per key, the
 * current window's and the previous window's, and estimate how many requests fall in the trailing
 * period by assuming the previous window's requests were spread evenly:
 *
 * <pre>
 *   elapsed  = fraction of the current window that has passed, in [0, 1)
 *   estimate = previous * (1 - elapsed) + current
 *   allowed  = estimate + 1 <= rate
 * </pre>
 *
 * It costs two integers per key (like fixed window) and smooths the boundary burst almost
 * entirely (like sliding log). Cloudflare measured it as misclassifying ~0.003% of requests
 * against real traffic.
 *
 * <p>Design questions:
 * <ul>
 *   <li>What happens to "previous" and "current" when a key is next seen two or more windows
 *       later? Make sure you do not weight stale data.</li>
 *   <li>Derive {@code retryAfter}. When denied, how long until the estimate drops enough to admit
 *       one request? There are two cases: the previous window is still contributing, or the
 *       current window alone is already over the limit (then you must wait until <em>after</em> the
 *       next boundary, plus some). The {@code retryAfterIsHonest} test will catch a lazy answer.</li>
 * </ul>
 */
public final class SlidingCounterLimiter implements Limiter, Sweepable {

    private final InstantSource clock;
    private final ConcurrentHashMap<String, Counters> counters = new ConcurrentHashMap<>();

    public SlidingCounterLimiter(InstantSource clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return "sliding_counter";
    }

    @Override
    public Decision allow(String key, Limit limit) {
        throw new ExerciseNotImplementedException(name());
    }

    /** Removes keys not seen for two full periods. */
    @Override
    public int sweep() {
        return 0;
    }

    @Override
    public int size() {
        return counters.size();
    }

    private static final class Counters {
    }
}
