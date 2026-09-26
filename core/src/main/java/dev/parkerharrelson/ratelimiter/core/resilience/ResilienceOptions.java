package dev.parkerharrelson.ratelimiter.core.resilience;

import dev.parkerharrelson.ratelimiter.core.Limiter;

import java.time.Duration;
import java.time.InstantSource;
import java.util.function.BiConsumer;

/**
 * Settings for {@link ResilientLimiter}.
 *
 * @param mode       failure behaviour
 * @param fallback   the local limiter used in {@link FailMode#LOCAL}; required for that mode
 * @param localShare scales limits handed to the fallback (about 1/replicas). Default 1.0
 * @param threshold  consecutive primary failures that open the breaker. Default 5; 0 disables
 * @param cooldown   how long the breaker stays open before probing. Default 5s
 * @param clock      drives the breaker's timers
 * @param onFallback invoked whenever a decision is made by the fallback path (mode, allowed)
 */
public record ResilienceOptions(
        FailMode mode,
        Limiter fallback,
        double localShare,
        int threshold,
        Duration cooldown,
        InstantSource clock,
        BiConsumer<FailMode, Boolean> onFallback) {

    public ResilienceOptions {
        if (mode == null) {
            throw new IllegalArgumentException("mode is required");
        }
        if (localShare <= 0) {
            localShare = 1.0;
        }
        if (cooldown == null || cooldown.isZero() || cooldown.isNegative()) {
            cooldown = Duration.ofSeconds(5);
        }
        if (clock == null) {
            clock = InstantSource.system();
        }
        if (onFallback == null) {
            onFallback = (m, a) -> { };
        }
    }

    public static ResilienceOptions of(FailMode mode) {
        return new ResilienceOptions(mode, null, 1.0, 5, Duration.ofSeconds(5), InstantSource.system(), null);
    }

    public ResilienceOptions withFallback(Limiter l) {
        return new ResilienceOptions(mode, l, localShare, threshold, cooldown, clock, onFallback);
    }

    public ResilienceOptions withLocalShare(double s) {
        return new ResilienceOptions(mode, fallback, s, threshold, cooldown, clock, onFallback);
    }

    public ResilienceOptions withThreshold(int t) {
        return new ResilienceOptions(mode, fallback, localShare, t, cooldown, clock, onFallback);
    }

    public ResilienceOptions withCooldown(Duration d) {
        return new ResilienceOptions(mode, fallback, localShare, threshold, d, clock, onFallback);
    }

    public ResilienceOptions withClock(InstantSource c) {
        return new ResilienceOptions(mode, fallback, localShare, threshold, cooldown, c, onFallback);
    }

    public ResilienceOptions withOnFallback(BiConsumer<FailMode, Boolean> cb) {
        return new ResilienceOptions(mode, fallback, localShare, threshold, cooldown, clock, cb);
    }
}
