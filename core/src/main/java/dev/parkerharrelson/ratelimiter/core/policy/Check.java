package dev.parkerharrelson.ratelimiter.core.policy;

import dev.parkerharrelson.ratelimiter.core.Limit;

/** One limiter call the middleware must make for a request. */
public record Check(String rule, String key, Limit limit) {
}
