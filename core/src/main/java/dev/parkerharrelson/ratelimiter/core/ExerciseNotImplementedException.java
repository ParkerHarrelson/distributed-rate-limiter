package dev.parkerharrelson.ratelimiter.core;

/**
 * Thrown by curriculum stubs that have not been written yet. The contract test suite recognises
 * it and reports the test as skipped instead of failed, so CI stays green while an exercise is in
 * progress.
 */
public final class ExerciseNotImplementedException extends UnsupportedOperationException {

    public ExerciseNotImplementedException(String what) {
        super(what + ": exercise not implemented yet (see docs/curriculum.md)");
    }
}
