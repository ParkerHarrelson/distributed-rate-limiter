package dev.parkerharrelson.ratelimiter.server;

import dev.parkerharrelson.ratelimiter.core.Limiter;
import dev.parkerharrelson.ratelimiter.core.policy.Policy;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the rate-limit filter on the API routes only; health, limits and metrics stay open. */
@Configuration
public class WebConfiguration {

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilter(Limiter limiter, Policy policy, MeterRegistry registry,
                                                            RuntimeSettings settings) {
        var filter = new RateLimitFilter(limiter, policy, registry, settings.instanceId(), settings.backend());
        var registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(1);
        return registration;
    }
}
