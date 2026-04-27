package com.polysign.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient beans for outbound HTTP calls.
 *
 * <p>IMPORTANT: callers MUST wrap every call with Resilience4j (retry + circuit breaker,
 * rate limiter where appropriate). No naked WebClient calls are permitted — see CONVENTIONS.md.
 * The Resilience4j instances are configured in {@code application.yml} under
 * {@code resilience4j.*} and injected via {@link io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry} etc.
 */
@Configuration
public class HttpConfig {

    /**
     * Polymarket Gamma metadata API — fetches all active markets.
     * Buffer raised to 16 MB: 200 markets × ~8 KB average (with long descriptions) ≈ 1.6 MB,
     * with 10× headroom for safety.
     */
    @Bean("gammaApiClient")
    public WebClient gammaApiClient() {
        return WebClient.builder()
                .baseUrl("https://gamma-api.polymarket.com")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, "polysign/0.1 (monitoring-bot; not-a-trading-bot)")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }

    /**
     * Polymarket CLOB (Central Limit Order Book) API — fetches per-token midpoint prices.
     * Endpoint used: {@code /midpoint?token_id={yesTokenId}} → {@code {"mid":"0.515"}}.
     * Rate-limited by Resilience4j to ≤10 calls/s to stay well within Polymarket's
     * undocumented limits.
     */
    @Bean("clobApiClient")
    public WebClient clobApiClient() {
        return WebClient.builder()
                .baseUrl("https://clob.polymarket.com")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, "polysign/0.1 (monitoring-bot; not-a-trading-bot)")
                .build();
    }

    /**
     * ntfy.sh push notification API — posts plain-text alert payloads.
     * Endpoint used: {@code POST /{topic}} with Title, Priority, Tags headers.
     * Wrapped in Resilience4j retry + circuit breaker by NotificationConsumer.
     */
    @Bean("ntfyClient")
    public WebClient ntfyClient() {
        return WebClient.builder()
                .baseUrl("https://ntfy.sh")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                .defaultHeader(HttpHeaders.USER_AGENT, "polysign/0.1 (monitoring-bot; not-a-trading-bot)")
                .build();
    }

}