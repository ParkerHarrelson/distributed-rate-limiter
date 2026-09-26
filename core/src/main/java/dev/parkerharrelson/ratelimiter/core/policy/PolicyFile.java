package dev.parkerharrelson.ratelimiter.core.policy;

/**
 * The parsed contents of {@code configs/limits.yaml}.
 *
 * @param algorithm default algorithm (fixed_window | sliding_counter | token_bucket); may be empty
 * @param failMode  default failure behaviour ("" | open | closed | local)
 * @param policy    the validated rules
 */
public record PolicyFile(String algorithm, String failMode, Policy policy) {
}
