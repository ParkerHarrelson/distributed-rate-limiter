package dev.parkerharrelson.ratelimiter.core.clock;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/**
 * A manually-advanced {@link InstantSource} for tests. Production code uses
 * {@link InstantSource#system()}; tests construct one of these and call {@link #advance} to move
 * time forward without sleeping. Safe for concurrent use.
 */
public final class MutableClock implements InstantSource {

    private Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    @Override
    public synchronized Instant instant() {
        return now;
    }

    public synchronized void advance(Duration d) {
        now = now.plus(d);
    }

    public synchronized void set(Instant t) {
        now = t;
    }
}
