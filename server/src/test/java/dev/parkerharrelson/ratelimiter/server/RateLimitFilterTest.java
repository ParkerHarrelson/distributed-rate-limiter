package dev.parkerharrelson.ratelimiter.server;

import dev.parkerharrelson.ratelimiter.core.Decision;
import dev.parkerharrelson.ratelimiter.core.Limit;
import dev.parkerharrelson.ratelimiter.core.Limiter;
import dev.parkerharrelson.ratelimiter.core.LimiterUnavailableException;
import dev.parkerharrelson.ratelimiter.core.clock.MutableClock;
import dev.parkerharrelson.ratelimiter.core.memory.FixedWindowLimiter;
import dev.parkerharrelson.ratelimiter.core.policy.Policy;
import dev.parkerharrelson.ratelimiter.core.policy.Rule;
import dev.parkerharrelson.ratelimiter.core.policy.Scope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the filter: no Spring context, just servlet mocks. */
class RateLimitFilterTest {

    private final MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_700_000_000L));
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private RateLimitFilter filter(Limiter limiter, Rule... rules) {
        return new RateLimitFilter(limiter, new Policy(List.of(rules)), registry, "test-1", "memory");
    }

    private record Call(MockHttpServletResponse response, boolean reachedHandler) {
    }

    private static Call get(RateLimitFilter filter, String path, String user) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRequestURI(path);
        if (user != null) {
            req.addHeader(RateLimitFilter.HEADER_USER_ID, user);
        }
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);
        return new Call(resp, chain.getRequest() != null);
    }

    @Test
    void allowsUpToTheLimitThenReturns429WithHeaders() throws Exception {
        RateLimitFilter f = filter(new FixedWindowLimiter(clock),
                Rule.of("u", Scope.USER, Limit.of(2, Duration.ofSeconds(10))));

        for (int i = 0; i < 2; i++) {
            Call c = get(f, "/api/cheap", "alice");
            assertThat(c.reachedHandler()).isTrue();
            assertThat(c.response().getStatus()).isEqualTo(200);
            assertThat(c.response().getHeader(RateLimitFilter.HEADER_REMAINING)).isEqualTo(String.valueOf(1 - i));
            assertThat(c.response().getHeader(RateLimitFilter.HEADER_INSTANCE)).isEqualTo("test-1");
        }

        Call denied = get(f, "/api/cheap", "alice");
        assertThat(denied.reachedHandler()).isFalse();
        assertThat(denied.response().getStatus()).isEqualTo(429);
        assertThat(denied.response().getHeader(RateLimitFilter.HEADER_POLICY)).isEqualTo("u");
        assertThat(denied.response().getHeader(RateLimitFilter.HEADER_RETRY_AFTER)).isNotIn(null, "0");
        assertThat(denied.response().getContentAsString()).contains("rate limit exceeded");

        assertThat(get(f, "/api/cheap", "bob").response().getStatus()).as("another user is unaffected").isEqualTo(200);
        assertThat(registry.counter("ratelimiter.decisions", "rule", "u", "algorithm", "fixed_window", "result", "denied").count())
                .isEqualTo(1);
    }

    @Test
    void composesRulesAndReportsTheTightestOne() throws Exception {
        RateLimitFilter f = filter(new FixedWindowLimiter(clock),
                Rule.of("u", Scope.USER, Limit.of(10, Duration.ofSeconds(10))),
                new Rule("exp", Scope.USER_ENDPOINT, List.of("/api/expensive"), List.of(), Limit.of(1, Duration.ofSeconds(10))));

        Call first = get(f, "/api/expensive", "alice");
        assertThat(first.response().getStatus()).isEqualTo(200);
        assertThat(first.response().getHeader(RateLimitFilter.HEADER_POLICY)).as("tightest rule wins the headers").isEqualTo("exp");
        assertThat(first.response().getHeader(RateLimitFilter.HEADER_REMAINING)).isEqualTo("0");

        assertThat(get(f, "/api/expensive", "alice").response().getStatus()).isEqualTo(429);
        assertThat(get(f, "/api/cheap", "alice").response().getStatus()).as("cheap still under rule u").isEqualTo(200);
    }

    @Test
    void backendErrorIs503WhenNoResilienceLayerIsConfigured() throws Exception {
        Limiter broken = new Limiter() {
            @Override
            public Decision allow(String key, Limit limit) {
                throw new LimiterUnavailableException("redis: connection refused");
            }

            @Override
            public String name() {
                return "broken";
            }
        };
        RateLimitFilter f = filter(broken, Rule.of("u", Scope.USER, Limit.of(2, Duration.ofSeconds(1))));
        Call c = get(f, "/api/cheap", "alice");
        assertThat(c.reachedHandler()).isFalse();
        assertThat(c.response().getStatus()).isEqualTo(503);
        assertThat(registry.counter("ratelimiter.backend.errors").count()).isEqualTo(1);
    }

    @Test
    void identifiesByHeaderThenByIp() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/cheap");
        req.setRemoteAddr("10.1.2.3");
        assertThat(RateLimitFilter.identify(req)).isEqualTo("ip:10.1.2.3");
        req.addHeader(RateLimitFilter.HEADER_USER_ID, "alice");
        assertThat(RateLimitFilter.identify(req)).isEqualTo("alice");
    }
}
