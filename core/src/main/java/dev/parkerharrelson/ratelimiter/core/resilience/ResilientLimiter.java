package dev.parkerharrelson.ratelimiter.core.resilience;

import dev.parkerharrelson.ratelimiter.core.Decision;
import dev.parkerharrelson.ratelimiter.core.ExerciseNotImplementedException;
import dev.parkerharrelson.ratelimiter.core.Limit;
import dev.parkerharrelson.ratelimiter.core.Limiter;

/**
 * Decides what happens when the distributed limiter cannot answer - EXERCISE 5.
 *
 * <p>A Redis-backed limiter fails in two ways: quickly (connection refused) or slowly (timeout
 * under a partition or an overloaded node). Both must be handled without making the protected
 * service worse than it would be with no rate limiter at all. The primary signals failure by
 * throwing {@link dev.parkerharrelson.ratelimiter.core.LimiterUnavailableException}; this class
 * must never let that escape.
 *
 * <p>Circuit breaker: paying the full Redis timeout on every request during an outage adds
 * latency to every call for the whole outage. After {@code threshold} consecutive failures the
 * breaker opens and requests go straight to the fallback for {@code cooldown}; then one probe
 * request is let through to test recovery. Same pattern as Resilience4j's CircuitBreaker;
 * implement it by hand so you know what is inside the box.
 *
 * <p>Design questions:
 * <ul>
 *   <li>Does a timeout count as a "failure" for the breaker? Does an {@code IllegalArgumentException}?</li>
 *   <li>When the breaker is half-open and the probe is in flight, what happens to the requests
 *       that arrive meanwhile? Exactly one probe, or a percentage?</li>
 *   <li>{@code localShare} scales rate; should it also scale burst? What about rate 1, share 0.33?</li>
 *   <li>Which JDK primitive holds the breaker state safely under concurrency: {@code volatile},
 *       {@code AtomicInteger}/{@code AtomicReference}, {@code synchronized}, or a
 *       {@code ReentrantLock}? The tests run multi-threaded.</li>
 * </ul>
 */
public final class ResilientLimiter implements Limiter {

    private final Limiter primary;
    private final ResilienceOptions options;
    // Add your breaker state here.

    public ResilientLimiter(Limiter primary, ResilienceOptions options) {
        if (options.mode() == FailMode.LOCAL && options.fallback() == null) {
            throw new IllegalArgumentException("LOCAL mode needs a fallback limiter");
        }
        this.primary = primary;
        this.options = options;
    }

    /** Transparent for metrics: reports the primary's algorithm. */
    @Override
    public String name() {
        return primary.name();
    }

    @Override
    public Decision allow(String key, Limit limit) {
        throw new ExerciseNotImplementedException("resilient limiter");
    }

    /** Breaker state for tests and {@code /healthz}: {@code closed}, {@code open} or {@code half_open}. */
    public String state() {
        return "closed";
    }
}
