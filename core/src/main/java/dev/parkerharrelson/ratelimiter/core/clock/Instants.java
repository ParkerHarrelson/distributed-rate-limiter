package dev.parkerharrelson.ratelimiter.core.clock;

import java.time.Instant;

/**
 * Nanosecond arithmetic on {@link Instant}. Window algorithms need
 * {@code floor(now / period)}, which is much easier on a single long than on an Instant.
 * Epoch nanoseconds fit in a long until the year 2262.
 */
public final class Instants {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private Instants() {
    }

    public static long epochNanos(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), NANOS_PER_SECOND), instant.getNano());
    }

    public static Instant ofEpochNanos(long nanos) {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, NANOS_PER_SECOND), Math.floorMod(nanos, NANOS_PER_SECOND));
    }
}
