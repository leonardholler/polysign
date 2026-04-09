package com.polysign.config;

import org.springframework.context.annotation.Configuration;

/**
 * WebClient + Resilience4j HTTP configuration.
 * Populated in Phase 2/3 when outbound HTTP calls (Polymarket, ntfy.sh, etc.) are wired.
 *
 * Every outbound HTTP call MUST go through Resilience4j (retry + circuit breaker).
 * No naked WebClient calls are permitted — see CONVENTIONS.md.
 */
@Configuration
public class HttpConfig {
    // TODO Phase 2: define WebClient beans with Resilience4j retry / circuit-breaker decorators
}
