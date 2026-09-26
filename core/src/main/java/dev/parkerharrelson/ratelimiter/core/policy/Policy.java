package dev.parkerharrelson.ratelimiter.core.policy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides WHICH limits apply to a request. The {@code Limiter} answers "may this key make another
 * request under this limit?"; this class answers "what keys and what limits does this request
 * map to?". A request is identified by (userId, endpoint); all matching rules apply and the
 * request must pass every one of them.
 */
public final class Policy {

    private final List<Rule> rules;

    public Policy(List<Rule> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("policy: at least one rule is required");
        }
        Set<String> names = new HashSet<>();
        for (Rule r : rules) {
            if (!names.add(r.name())) {
                throw new IllegalArgumentException("policy: duplicate rule name '" + r.name() + "'");
            }
        }
        this.rules = List.copyOf(rules);
    }

    /** Every check that applies to the request, in rule order. Empty means "not rate limited". */
    public List<Check> resolve(String userId, String endpoint) {
        List<Check> checks = new ArrayList<>(rules.size());
        for (Rule r : rules) {
            if (r.matches(userId, endpoint)) {
                checks.add(new Check(r.name(), r.key(userId, endpoint), r.limit()));
            }
        }
        return checks;
    }

    public List<Rule> rules() {
        return rules;
    }
}
