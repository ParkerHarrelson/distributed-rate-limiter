package dev.parkerharrelson.ratelimiter.core;

/** The backing store could not be reached or did not answer in time. */
public class LimiterUnavailableException extends RuntimeException {

    public LimiterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public LimiterUnavailableException(String message) {
        super(message);
    }
}
