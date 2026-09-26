package dev.parkerharrelson.ratelimiter.core.policy;

/** Which request attributes become part of a rule's key. */
public enum Scope {
    /** One bucket per user across all endpoints. */
    USER,
    /** One bucket per endpoint shared by all users. */
    ENDPOINT,
    /** One bucket per (user, endpoint) pair. */
    USER_ENDPOINT,
    /** One bucket for everything. */
    GLOBAL;

    public static Scope parse(String s) {
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown scope '" + s + "' (want user|endpoint|user_endpoint|global)");
        }
    }

    public String configName() {
        return name().toLowerCase();
    }
}
