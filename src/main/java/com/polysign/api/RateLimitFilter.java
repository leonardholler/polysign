package com.polysign.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polysign.model.ApiKey;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Per-API-key rate limiting using Resilience4j.
 *
 * <p>Runs AFTER {@link ApiKeyAuthFilter} (order=20). If no {@link ApiKey} is present in the
 * request attribute (auth failed, or unauthenticated path), passes through immediately.
 *
 * <p>Rate limiters are created lazily, keyed by {@code apiKeyHash}:
 * <ul>
 *   <li>FREE tier: {@code polysign.auth.rate-limits.free} requests / minute (default 60)</li>
 *   <li>PRO tier:  {@code polysign.auth.rate-limits.pro}  requests / minute (default 600)</li>
 * </ul>
 *
 * <p>Every authenticated response includes:
 * <ul>
 *   <li>{@code X-RateLimit-Limit}     — the per-minute cap for this key</li>
 *   <li>{@code X-RateLimit-Remaining} — remaining calls in the current window</li>
 *   <li>{@code X-RateLimit-Reset}     — Unix epoch seconds when the window resets</li>
 * </ul>
 *
 * <p>On 429, {@code Retry-After} is also set. The rejected request does NOT consume a permit.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String HDR_LIMIT     = "X-RateLimit-Limit";
    private static final String HDR_REMAINING = "X-RateLimit-Remaining";
    private static final String HDR_RESET     = "X-RateLimit-Reset";
    private static final String HDR_RETRY     = "Retry-After";
    private static final Duration PERIOD      = Duration.ofMinutes(1);

    private final RateLimiterRegistry rateLimiterRegistry;
    private final MeterRegistry       meterRegistry;
    private final int                 freeLimit;
    private final int                 proLimit;

    public RateLimitFilter(RateLimiterRegistry rateLimiterRegistry,
                           MeterRegistry meterRegistry,
                           int freeLimit,
                           int proLimit) {
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.meterRegistry       = meterRegistry;
        this.freeLimit           = freeLimit;
        this.proLimit            = proLimit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Optional<ApiKey> apiKeyOpt = ApiKeyContext.getApiKey(request);
        if (apiKeyOpt.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }

        ApiKey apiKey = apiKeyOpt.get();
        RateLimiter rateLimiter = getRateLimiter(apiKey);

        long resetEpochSec = nextResetEpochSec();
        int limit = apiKey.getRateLimit() != null ? apiKey.getRateLimit()
                : ("PRO".equals(apiKey.getTier()) ? proLimit : freeLimit);

        boolean acquired = rateLimiter.acquirePermission();
        int available = rateLimiter.getMetrics().getAvailablePermissions();

        if (acquired) {
            response.setHeader(HDR_LIMIT,     String.valueOf(limit));
            response.setHeader(HDR_REMAINING, String.valueOf(Math.max(0, available)));
            response.setHeader(HDR_RESET,     String.valueOf(resetEpochSec));
            chain.doFilter(request, response);
        } else {
            long retryAfter = Math.max(1, resetEpochSec - Instant.now().getEpochSecond());
            log.debug("event=rate_limited client={} prefix={}", apiKey.getClientName(), apiKey.getKeyPrefix());
            meterRegistry.counter("polysign.api.rate_limited",
                    "clientName", apiKey.getClientName()).increment();

            response.setStatus(429);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.setHeader(HDR_LIMIT,     String.valueOf(limit));
            response.setHeader(HDR_REMAINING, "0");
            response.setHeader(HDR_RESET,     String.valueOf(resetEpochSec));
            response.setHeader(HDR_RETRY,     String.valueOf(retryAfter));
            response.getWriter().write(
                    MAPPER.writeValueAsString(Map.of("error", "Rate limit exceeded",
                                                     "retryAfter", retryAfter)));
        }
    }

    private RateLimiter getRateLimiter(ApiKey apiKey) {
        int limit = "PRO".equals(apiKey.getTier()) ? proLimit : freeLimit;
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(limit)
                .limitRefreshPeriod(PERIOD)
                .timeoutDuration(Duration.ZERO)
                .build();
        return rateLimiterRegistry.rateLimiter(apiKey.getApiKeyHash(), config);
    }

    /** Start of the next 1-minute boundary in Unix epoch seconds. */
    private static long nextResetEpochSec() {
        long now = Instant.now().getEpochSecond();
        long periodSec = PERIOD.toSeconds();
        return (now / periodSec + 1) * periodSec;
    }
}
