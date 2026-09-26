package dev.parkerharrelson.ratelimiter.core;

import java.time.Duration;

/**
 * The outcome of one {@link Limiter#allow} call. Fields map directly onto the de-facto standard
 * {@code X-RateLimit-*} and {@code Retry-After} response headers.
 *
 * @param allowed    whether the request may proceed
 * @param limit      the configured capacity ({@code X-RateLimit-Limit})
 * @param remaining  how many more requests would be allowed right now; 0 when denied
 * @param retryAfter how long to wait before the next request has a chance; zero when allowed
 * @param resetAfter how long until the key's budget is completely restored
 */
public record Decision(boolean allowed, int limit, int remaining, Duration retryAfter, Duration resetAfter) {

    public static Decision allow(int limit, int remaining, Duration resetAfter) {
        return new Decision(true, limit, Math.max(remaining, 0), Duration.ZERO, clamp(resetAfter));
    }

    public static Decision deny(int limit, Duration retryAfter, Duration resetAfter) {
        return new Decision(false, limit, 0, clamp(retryAfter), clamp(resetAfter));
    }

    private static Duration clamp(Duration d) {
        return d == null || d.isNegative() ? Duration.ZERO : d;
    }
}
