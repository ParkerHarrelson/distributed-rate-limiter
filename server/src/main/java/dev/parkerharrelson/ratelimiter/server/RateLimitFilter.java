package dev.parkerharrelson.ratelimiter.server;

import dev.parkerharrelson.ratelimiter.core.Decision;
import dev.parkerharrelson.ratelimiter.core.Limiter;
import dev.parkerharrelson.ratelimiter.core.LimiterUnavailableException;
import dev.parkerharrelson.ratelimiter.core.policy.Check;
import dev.parkerharrelson.ratelimiter.core.policy.Policy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Enforces the {@link Policy} using the {@link Limiter} on every {@code /api/**} request.
 *
 * <p>Every matching rule is evaluated and the request is denied if any denies. Note the subtlety
 * this creates: if rule A allows (and consumes a token) but rule B denies, A's token is gone even
 * though the request never ran. That over-counting is a known trade-off of composing independent
 * limits; see docs/curriculum.md (Exercise 6) for the fix.
 *
 * <p>Backend errors become HTTP 503. That is deliberately the dumbest possible behaviour: making
 * it smarter is Exercise 5 (the resilience layer), and the demo wants to show the "before".
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /** De-facto standard headers (GitHub, Stripe); Retry-After is RFC 9110. */
    public static final String HEADER_USER_ID = "X-User-ID";
    public static final String HEADER_LIMIT = "X-RateLimit-Limit";
    public static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    public static final String HEADER_RESET = "X-RateLimit-Reset";
    public static final String HEADER_POLICY = "X-RateLimit-Policy";
    public static final String HEADER_INSTANCE = "X-Instance";
    public static final String HEADER_RETRY_AFTER = "Retry-After";

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final Limiter limiter;
    private final Policy policy;
    private final MeterRegistry registry;
    private final String instanceId;
    private final String backend;

    public RateLimitFilter(Limiter limiter, Policy policy, MeterRegistry registry, String instanceId, String backend) {
        this.limiter = limiter;
        this.policy = policy;
        this.registry = registry;
        this.instanceId = instanceId;
        this.backend = backend;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String userId = identify(request);
        List<Check> checks = policy.resolve(userId, request.getRequestURI());
        response.setHeader(HEADER_INSTANCE, instanceId);

        // Track the tightest allowed decision so headers reflect the rule the client is closest
        // to exhausting.
        Decision tightest = null;
        String tightestRule = null;

        for (Check check : checks) {
            Decision d;
            try {
                d = allow(check);
            } catch (LimiterUnavailableException e) {
                log.warn("rate limiter unavailable rule={} key={} err={}", check.rule(), check.key(), e.getMessage());
                writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "rate limiter unavailable");
                return;
            }
            if (!d.allowed()) {
                setHeaders(response, d, check.rule());
                response.setHeader(HEADER_RETRY_AFTER, String.valueOf(Math.max(1, ceilSeconds(d.retryAfter()))));
                writeError(response, 429, "rate limit exceeded (" + check.rule() + "); retry in " + d.retryAfter().toMillis() + "ms");
                return;
            }
            if (tightest == null || d.remaining() < tightest.remaining()) {
                tightest = d;
                tightestRule = check.rule();
            }
        }
        if (tightest != null) {
            setHeaders(response, tightest, tightestRule);
        }
        chain.doFilter(request, response);
    }

    /** Runs one check and records metrics for it. */
    private Decision allow(Check check) {
        Timer.Sample sample = Timer.start(registry);
        String result = "allowed";
        try {
            Decision d = limiter.allow(check.key(), check.limit());
            if (!d.allowed()) {
                result = "denied";
            }
            return d;
        } catch (RuntimeException e) {
            result = "error";
            registry.counter("ratelimiter.backend.errors").increment();
            if (e instanceof LimiterUnavailableException) {
                throw e;
            }
            // Anything else (including a not-yet-implemented exercise) is still "could not decide".
            throw new LimiterUnavailableException(e.getMessage(), e);
        } finally {
            sample.stop(Timer.builder("ratelimiter.check.duration")
                    .description("Latency of a single limiter allow() call")
                    .publishPercentileHistogram()
                    .tags("algorithm", limiter.name(), "backend", backend)
                    .register(registry));
            registry.counter("ratelimiter.decisions", "rule", check.rule(), "algorithm", limiter.name(), "result", result)
                    .increment();
        }
    }

    private static void setHeaders(HttpServletResponse response, Decision d, String rule) {
        response.setHeader(HEADER_LIMIT, String.valueOf(d.limit()));
        response.setHeader(HEADER_REMAINING, String.valueOf(d.remaining()));
        response.setHeader(HEADER_RESET, String.valueOf(ceilSeconds(d.resetAfter())));
        response.setHeader(HEADER_POLICY, rule);
    }

    private static long ceilSeconds(Duration d) {
        return (d.toMillis() + 999) / 1000;
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }

    /** The caller identity: {@code X-User-ID}, else the client IP. */
    static String identify(HttpServletRequest request) {
        String id = request.getHeader(HEADER_USER_ID);
        return (id != null && !id.isBlank()) ? id : "ip:" + request.getRemoteAddr();
    }
}
