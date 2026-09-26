package dev.parkerharrelson.ratelimiter.core;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Human-friendly duration parsing and printing for config files and logs. */
public final class Durations {

    private static final Pattern SHORT = Pattern.compile("^(\\d+)(ms|s|m|h)$");

    private Durations() {
    }

    /** Parses {@code 250ms}, {@code 10s}, {@code 1m}, {@code 2h}, or an ISO-8601 duration. */
    public static Duration parse(String text) {
        String s = text.trim();
        Matcher m = SHORT.matcher(s);
        if (m.matches()) {
            long n = Long.parseLong(m.group(1));
            return switch (m.group(2)) {
                case "ms" -> Duration.ofMillis(n);
                case "s" -> Duration.ofSeconds(n);
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                default -> throw new IllegalArgumentException("unreachable");
            };
        }
        try {
            return Duration.parse(s);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("bad duration '" + text + "' (want e.g. 500ms, 10s, 1m, 1h)", e);
        }
    }

    /** Inverse of {@link #parse} for whole units; falls back to milliseconds. */
    public static String humanize(Duration d) {
        long ms = d.toMillis();
        if (ms % 3_600_000 == 0) {
            return (ms / 3_600_000) + "h";
        }
        if (ms % 60_000 == 0) {
            return (ms / 60_000) + "m";
        }
        if (ms % 1_000 == 0) {
            return (ms / 1_000) + "s";
        }
        return ms + "ms";
    }
}
