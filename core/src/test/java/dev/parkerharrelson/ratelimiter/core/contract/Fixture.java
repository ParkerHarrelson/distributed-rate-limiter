package dev.parkerharrelson.ratelimiter.core.contract;

import dev.parkerharrelson.ratelimiter.core.Limiter;

/** A fresh, empty limiter and the clock that drives it. Created once per test method. */
public record Fixture(Limiter limiter, TestClock clock) {
}
