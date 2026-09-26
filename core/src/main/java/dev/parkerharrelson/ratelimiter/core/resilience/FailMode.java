package dev.parkerharrelson.ratelimiter.core.resilience;

import java.util.Optional;

/** What to do when the distributed limiter cannot answer. */
public enum FailMode {
    /** Allow every request while the backend is failing. Availability over protection. */
    OPEN,
    /** Deny every request while the backend is failing. Protection over availability. */
    CLOSED,
    /** Fall back to an in-process limiter with a scaled-down limit. Approximate beats nothing. */
    LOCAL;

    public static Optional<FailMode> parse(String s) {
        if (s == null || s.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(s.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown fail mode '" + s + "' (want open|closed|local)");
        }
    }

    public String configName() {
        return name().toLowerCase();
    }
}
