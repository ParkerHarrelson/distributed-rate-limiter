package dev.parkerharrelson.ratelimiter.core.contract;

import java.time.Duration;
import java.time.Instant;

/**
 * Lets the contract suite read and move the limiter's notion of time. {@code MutableClock}
 * satisfies it instantly; real-clock (Redis) fixtures use {@link SleepClock}.
 */
public interface TestClock {

    Instant now();

    void advance(Duration d);

    /** For limiters that read the real wall clock: {@code advance} actually sleeps. */
    final class SleepClock implements TestClock {
        @Override
        public Instant now() {
            return Instant.now();
        }

        @Override
        public void advance(Duration d) {
            try {
                Thread.sleep(d);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
