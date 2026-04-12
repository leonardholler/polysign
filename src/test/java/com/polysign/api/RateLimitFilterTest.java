package com.polysign.api;

import com.polysign.model.ApiKey;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private static final int TEST_FREE_LIMIT = 2; // use 2 so test is fast
    private static final int TEST_PRO_LIMIT  = 10;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        RateLimiterConfig defaultConfig = RateLimiterConfig.custom()
                .limitForPeriod(TEST_FREE_LIMIT)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO)
                .build();
        RateLimiterRegistry registry = RateLimiterRegistry.of(defaultConfig);
        filter = new RateLimitFilter(registry, new SimpleMeterRegistry(),
                TEST_FREE_LIMIT, TEST_PRO_LIMIT);
    }

    @Test
    void noApiKeyInAttribute_passesThrough() throws Exception {
        MockHttpServletRequest  req   = new MockHttpServletRequest();
        MockHttpServletResponse res   = new MockHttpServletResponse();
        MockFilterChain         chain = new MockFilterChain();

        filter.doFilterInternal(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("X-RateLimit-Limit")).isNull();
    }

    @Test
    void withinLimit_setsRateLimitHeaders() throws Exception {
        ApiKey key = freeKey("hash-a");
        MockHttpServletRequest req = requestWith(key);
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(req, res, new MockFilterChain());

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("X-RateLimit-Limit")).isEqualTo(String.valueOf(TEST_FREE_LIMIT));
        assertThat(res.getHeader("X-RateLimit-Remaining")).isNotNull();
        assertThat(res.getHeader("X-RateLimit-Reset")).isNotNull();

        long reset = Long.parseLong(res.getHeader("X-RateLimit-Reset"));
        assertThat(reset).isGreaterThan(Instant.now().getEpochSecond());
    }

    @Test
    void remainingDecrementsOnEachRequest() throws Exception {
        ApiKey key = freeKey("hash-b");

        MockHttpServletResponse res1 = new MockHttpServletResponse();
        filter.doFilterInternal(requestWith(key), res1, new MockFilterChain());

        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter.doFilterInternal(requestWith(key), res2, new MockFilterChain());

        int remaining1 = Integer.parseInt(res1.getHeader("X-RateLimit-Remaining"));
        int remaining2 = Integer.parseInt(res2.getHeader("X-RateLimit-Remaining"));
        assertThat(remaining2).isLessThanOrEqualTo(remaining1);
    }

    @Test
    void exceedingLimit_returns429WithAllHeaders() throws Exception {
        ApiKey key = freeKey("hash-c");

        // Exhaust the limit (TEST_FREE_LIMIT = 2)
        for (int i = 0; i < TEST_FREE_LIMIT; i++) {
            filter.doFilterInternal(requestWith(key), new MockHttpServletResponse(), new MockFilterChain());
        }

        // Next request should be 429
        MockHttpServletResponse overLimit = new MockHttpServletResponse();
        filter.doFilterInternal(requestWith(key), overLimit, new MockFilterChain());

        assertThat(overLimit.getStatus()).isEqualTo(429);
        assertThat(overLimit.getHeader("X-RateLimit-Limit")).isEqualTo(String.valueOf(TEST_FREE_LIMIT));
        assertThat(overLimit.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(overLimit.getHeader("X-RateLimit-Reset")).isNotNull();
        assertThat(overLimit.getHeader("Retry-After")).isNotNull();
        assertThat(overLimit.getContentAsString()).contains("Rate limit exceeded");
        assertThat(overLimit.getContentAsString()).contains("retryAfter");
    }

    @Test
    void resetIsValidFutureTimestamp() throws Exception {
        ApiKey key = freeKey("hash-d");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilterInternal(requestWith(key), res, new MockFilterChain());

        long reset = Long.parseLong(res.getHeader("X-RateLimit-Reset"));
        long now   = Instant.now().getEpochSecond();
        assertThat(reset).isGreaterThan(now);
        assertThat(reset).isLessThanOrEqualTo(now + 60);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ApiKey freeKey(String hash) {
        ApiKey k = new ApiKey();
        k.setApiKeyHash(hash);
        k.setClientName("test-client");
        k.setTier("FREE");
        k.setRateLimit(TEST_FREE_LIMIT);
        k.setActive(true);
        k.setKeyPrefix(hash.substring(0, Math.min(8, hash.length())));
        return k;
    }

    private static MockHttpServletRequest requestWith(ApiKey apiKey) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        ApiKeyContext.setApiKey(req, apiKey);
        return req;
    }
}
