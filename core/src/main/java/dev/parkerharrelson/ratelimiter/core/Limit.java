package dev.parkerharrelson.ratelimiter.core;

import java.time.Duration;

/**
 * How much traffic a single key may generate.
 *
 * <p>{@code rate} events are permitted per {@code period} on a sustained basis. {@code burst} is
 * the maximum number of events permitted instantaneously; windowed algorithms ignore it (the
 * window itself bounds the burst) and the token bucket uses it as the bucket capacity. A burst
 * of 0 means "same as rate".
 */
public record Limit(int rate, Duration period, int burst) {

    public Limit {
        if (rate <= 0) {
            throw new IllegalArgumentException("rate must be > 0, got " + rate);
        }
        if (period == null || period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("period must be > 0, got " + period);
        }
        if (burst < 0) {
            throw new IllegalArgumentException("burst must be >= 0, got " + burst);
        }
    }

    /** A limit whose burst equals its rate. */
    public static Limit of(int rate, Duration period) {
        return new Limit(rate, period, 0);
    }

    /** The effective burst size. */
    public int capacity() {
        return burst > 0 ? burst : rate;
    }

    /** Renders like the config file does, e.g. {@code 100/1m (burst 20)}. */
    @Override
    public String toString() {
        String base = rate + "/" + Durations.humanize(period);
        return (burst > 0 && burst != rate) ? base + " (burst " + burst + ")" : base;
    }
}
