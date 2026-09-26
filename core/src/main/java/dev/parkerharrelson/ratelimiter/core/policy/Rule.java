package dev.parkerharrelson.ratelimiter.core.policy;

import dev.parkerharrelson.ratelimiter.core.Limit;

import java.util.List;

/**
 * One configured limit. Empty {@code endpoints}/{@code users} mean "all". Patterns match exactly
 * or, with a trailing {@code *}, by prefix (e.g. {@code vip-*}).
 */
public record Rule(String name, Scope scope, List<String> endpoints, List<String> users, Limit limit) {

    public Rule {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("rule has no name");
        }
        if (name.contains(":") || name.contains(" ")) {
            throw new IllegalArgumentException("rule '" + name + "': name may not contain ':' or spaces");
        }
        if (scope == null) {
            throw new IllegalArgumentException("rule '" + name + "': scope is required");
        }
        if (limit == null) {
            throw new IllegalArgumentException("rule '" + name + "': limit is required");
        }
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        users = users == null ? List.of() : List.copyOf(users);
    }

    public static Rule of(String name, Scope scope, Limit limit) {
        return new Rule(name, scope, List.of(), List.of(), limit);
    }

    boolean matches(String userId, String endpoint) {
        return matchAny(users, userId) && matchAny(endpoints, endpoint);
    }

    private static boolean matchAny(List<String> patterns, String value) {
        if (patterns.isEmpty()) {
            return true;
        }
        for (String p : patterns) {
            if (p.endsWith("*")) {
                if (value.startsWith(p.substring(0, p.length() - 1))) {
                    return true;
                }
            } else if (p.equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** Colons separate the parts; rule names cannot contain colons, so keys are unambiguous. */
    String key(String userId, String endpoint) {
        return switch (scope) {
            case USER -> name + ":" + userId;
            case ENDPOINT -> name + ":" + endpoint;
            case USER_ENDPOINT -> name + ":" + userId + ":" + endpoint;
            case GLOBAL -> name;
        };
    }
}
