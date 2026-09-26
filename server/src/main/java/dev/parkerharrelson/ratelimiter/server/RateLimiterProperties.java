package dev.parkerharrelson.ratelimiter.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Runtime settings for this process (bound from {@code application.yml}, which reads the
 * environment). Anything describing <em>traffic policy</em> lives in the limits YAML instead.
 *
 * @param instanceId   names this replica in logs and headers
 * @param backend      {@code redis} or {@code memory}
 * @param redisAddr    host:port
 * @param redisTimeout bound on each limiter call
 * @param limitsFile   path to the policy YAML
 * @param algorithm    overrides the YAML default when non-empty
 * @param failMode     overrides the YAML default when non-empty
 * @param localShare   this replica's fraction of a limit when falling back to local enforcement
 * @param clockSkew    shifts this replica's clock for chaos experiments (memory backend only)
 */
@ConfigurationProperties(prefix = "ratelimiter")
public record RateLimiterProperties(
        String instanceId,
        String backend,
        String redisAddr,
        Duration redisTimeout,
        String limitsFile,
        String algorithm,
        String failMode,
        double localShare,
        Duration clockSkew) {

    public RateLimiterProperties {
        if (!"redis".equals(backend) && !"memory".equals(backend)) {
            throw new IllegalArgumentException("ratelimiter.backend must be redis or memory, got '" + backend + "'");
        }
        if (redisTimeout == null) {
            redisTimeout = Duration.ofMillis(50);
        }
        if (clockSkew == null) {
            clockSkew = Duration.ZERO;
        }
        if (localShare <= 0) {
            localShare = 1.0;
        }
    }
}
