package dev.parkerharrelson.ratelimiter.server;

import dev.parkerharrelson.ratelimiter.core.Limiter;
import dev.parkerharrelson.ratelimiter.core.policy.Policy;
import dev.parkerharrelson.ratelimiter.core.resilience.ResilientLimiter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Not rate limited: {@code /healthz} for scripts and load balancers, {@code /limits} to inspect policy. */
@RestController
public class HealthController {

    public record Health(String status, String instanceId, String algorithm, String backend, String failMode, String breaker) {
    }

    public record RuleView(String name, String scope, List<String> endpoints, List<String> users, String limit) {
    }

    private final RuntimeSettings settings;
    private final Limiter limiter;
    private final Policy policy;

    public HealthController(RuntimeSettings settings, Limiter limiter, Policy policy) {
        this.settings = settings;
        this.limiter = limiter;
        this.policy = policy;
    }

    @GetMapping("/healthz")
    public Health healthz() {
        String breaker = limiter instanceof ResilientLimiter r ? r.state() : null;
        return new Health("ok", settings.instanceId(), settings.algorithm(), settings.backend(), settings.failModeName(), breaker);
    }

    @GetMapping("/limits")
    public List<RuleView> limits() {
        return policy.rules().stream()
                .map(r -> new RuleView(r.name(), r.scope().configName(), r.endpoints(), r.users(), r.limit().toString()))
                .toList();
    }
}
