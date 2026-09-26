package dev.parkerharrelson.ratelimiter.core.memory;

import dev.parkerharrelson.ratelimiter.core.Decision;
import dev.parkerharrelson.ratelimiter.core.ExerciseNotImplementedException;
import dev.parkerharrelson.ratelimiter.core.Limit;
import dev.parkerharrelson.ratelimiter.core.Limiter;

import java.time.InstantSource;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token bucket - EXERCISE 1 (see docs/curriculum.md).
 *
 * <p>Mental model: every key owns a bucket that holds at most {@code limit.capacity()} tokens.
 * Tokens drip in continuously at {@code limit.rate()} per {@code limit.period()}. A request takes
 * one token if one is available and is denied otherwise.
 *
 * <p>Unlike the windowed algorithms, a token bucket separates two concerns that fixed windows
 * conflate: the sustained rate (rate/period) and the maximum instantaneous burst (burst). That is
 * why it is the algorithm behind most production API rate limiters, and behind Guava's
 * {@code RateLimiter} and Bucket4j in the Java world.
 *
 * <p>Design questions to answer before writing code:
 * <ul>
 *   <li>You should NOT run a scheduled task per bucket that adds tokens on a timer. How do you
 *       compute how many tokens a bucket holds <em>right now</em> given only what you stored on the
 *       previous request?</li>
 *   <li>Tokens refill fractionally (half a token after half an interval). Do you store a
 *       {@code double}, or a {@code long} in some smaller unit (nanos-worth-of-tokens)? What are the
 *       failure modes of each? Think about very long idle periods and about equality comparisons.</li>
 *   <li>What is the exact {@code retryAfter} when a request is denied with {@code t} tokens in the
 *       bucket, {@code 0 <= t < 1}?</li>
 *   <li>What must be true about the state you store so that a brand-new key starts with a
 *       <em>full</em> bucket rather than an empty one?</li>
 *   <li>Which concurrency pattern: a synchronized per-key object like {@link FixedWindowLimiter},
 *       {@code ConcurrentHashMap.compute} returning a new immutable record, or a CAS loop on an
 *       {@code AtomicReference}? Pick one and be ready to defend it; the JMH benchmark will judge.</li>
 * </ul>
 *
 * The contract tests in {@code TokenBucketLimiterTest} are the acceptance criteria.
 */
public final class TokenBucketLimiter implements Limiter, Sweepable {

    private final InstantSource clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketLimiter(InstantSource clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return "token_bucket";
    }

    @Override
    public Decision allow(String key, Limit limit) {
        throw new ExerciseNotImplementedException(name());
    }

    /**
     * Removes buckets that have been idle long enough to be full again (a full bucket is
     * indistinguishable from a missing one, so it is safe to drop).
     */
    @Override
    public int sweep() {
        return 0;
    }

    @Override
    public int size() {
        return buckets.size();
    }

    /** What a bucket remembers between requests. Add whatever fields you decide you need. */
    private static final class Bucket {
    }
}
