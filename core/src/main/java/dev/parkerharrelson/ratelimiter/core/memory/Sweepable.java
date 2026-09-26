package dev.parkerharrelson.ratelimiter.core.memory;

/**
 * In-memory limiters accumulate state for every key they have ever seen. Something must
 * periodically evict what is no longer needed, or memory grows without bound.
 */
public interface Sweepable {

    /** Removes state that can no longer affect any decision. Returns how many keys were removed. */
    int sweep();

    /** How many keys currently hold state. */
    int size();
}
