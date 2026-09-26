package dev.parkerharrelson.ratelimiter.server;

import dev.parkerharrelson.ratelimiter.core.resilience.FailMode;

import java.util.Optional;

/** The effective algorithm and fail mode after merging env overrides with the limits file. */
public record RuntimeSettings(String algorithm, Optional<FailMode> failMode, String backend, String instanceId) {

    public String failModeName() {
        return failMode.map(FailMode::configName).orElse("");
    }
}
