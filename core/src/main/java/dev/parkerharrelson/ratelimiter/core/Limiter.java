package dev.parkerharrelson.ratelimiter.core;

/**
 * The contract every rate-limiting algorithm in this project implements, whether it keeps state
 * in local memory or in Redis.
 *
 * <p>{@link #allow} atomically records one request against {@code key} and reports whether it is
 * permitted under {@code limit}. Implementations must be safe for concurrent use and must never
 * allow more than {@code limit.capacity()} requests within the limit's window, regardless of how
 * many threads or service replicas call them.
 *
 * <p>A {@link LimiterUnavailableException} means the limiter could not make a decision (for
 * example the backing store is unreachable). Callers decide how to fail; see the
 * {@code resilience} package.
 */
public interface Limiter {

    Decision allow(String key, Limit limit);

    /** Identifies the algorithm for metrics and logs, e.g. {@code token_bucket}. */
    String name();
}
