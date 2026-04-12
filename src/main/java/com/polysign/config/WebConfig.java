package com.polysign.config;

import com.polysign.api.ApiKeyAuthFilter;
import com.polysign.api.RateLimitFilter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the API authentication and rate-limit filters on {@code /api/v1/*} only.
 *
 * <p>Paths outside this pattern ({@code /}, {@code /actuator/**}, {@code /api/docs/**})
 * never reach these filters — no extra exclusion logic needed.
 *
 * <p>Filter order:
 * <ol>
 *   <li>10 — {@link ApiKeyAuthFilter}: validates X-API-Key, populates request attribute</li>
 *   <li>20 — {@link RateLimitFilter}: per-key Resilience4j rate limiter, sets quota headers</li>
 * </ol>
 */
@Configuration
public class WebConfig {

    /** Servlet URL pattern that covers all versioned API paths. */
    private static final String V1_PATTERN = "/api/v1/*";

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(
            ApiKeyRepository apiKeyRepository) {
        FilterRegistrationBean<ApiKeyAuthFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new ApiKeyAuthFilter(apiKeyRepository));
        bean.addUrlPatterns(V1_PATTERN);
        bean.setOrder(10);
        bean.setName("apiKeyAuthFilter");
        return bean;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RateLimiterRegistry rateLimiterRegistry,
            MeterRegistry meterRegistry,
            @Value("${polysign.auth.rate-limits.free:60}") int freeLimit,
            @Value("${polysign.auth.rate-limits.pro:600}") int proLimit) {
        FilterRegistrationBean<RateLimitFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new RateLimitFilter(rateLimiterRegistry, meterRegistry, freeLimit, proLimit));
        bean.addUrlPatterns(V1_PATTERN);
        bean.setOrder(20);
        bean.setName("rateLimitFilter");
        return bean;
    }
}
