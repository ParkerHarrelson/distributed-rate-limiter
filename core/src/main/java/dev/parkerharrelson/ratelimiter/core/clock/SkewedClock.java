package dev.parkerharrelson.ratelimiter.core.clock;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/** The system clock shifted by a fixed offset. Used to demonstrate clock-skew failures. */
public record SkewedClock(Duration offset) implements InstantSource {

    @Override
    public Instant instant() {
        return Instant.now().plus(offset);
    }
}
