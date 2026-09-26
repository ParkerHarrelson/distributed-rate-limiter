package dev.parkerharrelson.ratelimiter.server;

import dev.parkerharrelson.ratelimiter.core.memory.Sweepable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** Periodically evicts stale in-memory limiter state so the process does not leak keys. */
@Component
public class SweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(SweepScheduler.class);

    private final List<Sweepable> sweepables;

    public SweepScheduler(List<Sweepable> sweepables) {
        this.sweepables = sweepables;
    }

    @Scheduled(fixedDelayString = "PT1M")
    public void sweep() {
        for (Sweepable s : sweepables) {
            int removed = s.sweep();
            if (removed > 0) {
                log.debug("swept {} keys from {}", removed, s.getClass().getSimpleName());
            }
        }
    }
}
