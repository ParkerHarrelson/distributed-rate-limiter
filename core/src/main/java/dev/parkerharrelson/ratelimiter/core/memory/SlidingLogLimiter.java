package dev.parkerharrelson.ratelimiter.core.memory;

import dev.parkerharrelson.ratelimiter.core.Decision;
import dev.parkerharrelson.ratelimiter.core.ExerciseNotImplementedException;
import dev.parkerharrelson.ratelimiter.core.Limit;
import dev.parkerharrelson.ratelimiter.core.Limiter;

import java.time.InstantSource;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding log - EXERCISE 2a (see docs/curriculum.md).
 *
 * <p>The exact sliding window: remember the timestamp of every allowed request in the trailing
 * period. A request is allowed iff fewer than {@code limit.rate()} timestamps are newer than
 * {@code now - period}.
 *
 * <p>It is perfectly accurate and has no boundary-burst problem. Its cost is memory: O(rate)
 * timestamps per key. At 10,000 requests/minute per user that is 80 KB per user per minute of
 * activity, which is exactly why the counter approximation exists.
 *
 * <p>Design questions:
 * <ul>
 *   <li>Which data structure lets you (a) append the newest timestamp and (b) discard everything
 *       older than a cutoff, both cheaply? {@code ArrayDeque}? A plain {@code long[]} ring buffer?
 *       {@code TreeMap}? What is the worst case of each?</li>
 *   <li>Do you record denied requests in the log? What changes if you do? (This is a real design
 *       fork; some products do, some do not.)</li>
 *   <li>When denied, {@code retryAfter} is <em>not</em> "until the window resets". What is it?</li>
 * </ul>
 */
public final class SlidingLogLimiter implements Limiter, Sweepable {

    private final InstantSource clock;
    private final ConcurrentHashMap<String, Log> logs = new ConcurrentHashMap<>();

    public SlidingLogLimiter(InstantSource clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return "sliding_log";
    }

    @Override
    public Decision allow(String key, Limit limit) {
        throw new ExerciseNotImplementedException(name());
    }

    /** Removes keys whose entire log is older than their period. */
    @Override
    public int sweep() {
        return 0;
    }

    @Override
    public int size() {
        return logs.size();
    }

    private static final class Log {
    }
}
