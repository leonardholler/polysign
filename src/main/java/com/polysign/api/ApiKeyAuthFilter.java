package com.polysign.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polysign.config.ApiKeyRepository;
import com.polysign.model.ApiKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Authenticates requests to {@code /api/v1/**} via the {@code X-API-Key} header.
 *
 * <p>Registered only for {@code /api/v1/*} via {@link com.polysign.config.WebConfig} —
 * paths like {@code /}, {@code /actuator/**}, and {@code /api/docs/**} never reach this filter.
 *
 * <p>On success, the {@link ApiKey} is stored in the request attribute
 * {@code polysign.apiKey} for downstream filters and controllers to consume via
 * {@link ApiKeyContext#getApiKey(HttpServletRequest)}.
 *
 * <p>Raw keys are NEVER logged. Only {@code keyPrefix} (first 8 chars) appears in logs.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HEADER = "X-API-Key";

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyAuthFilter(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String rawKey = request.getHeader(HEADER);

        if (rawKey == null || rawKey.isBlank()) {
            reject(response, "Invalid or missing API key", "Pass your key in the X-API-Key header");
            return;
        }

        Optional<ApiKey> found = apiKeyRepository.findByRawKey(rawKey);

        if (found.isEmpty()) {
            log.warn("event=api_key_not_found prefix={}", prefix(rawKey));
            reject(response, "Invalid or missing API key", "Pass your key in the X-API-Key header");
            return;
        }

        ApiKey apiKey = found.get();

        if (!Boolean.TRUE.equals(apiKey.getActive())) {
            log.warn("event=api_key_inactive prefix={}", apiKey.getKeyPrefix());
            reject(response, "Invalid or missing API key", "Pass your key in the X-API-Key header");
            return;
        }

        log.debug("event=api_key_authenticated prefix={} client={}", apiKey.getKeyPrefix(), apiKey.getClientName());
        ApiKeyContext.setApiKey(request, apiKey);
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, String error, String hint) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(MAPPER.writeValueAsString(Map.of("error", error, "hint", hint)));
    }

    private static String prefix(String rawKey) {
        return rawKey.substring(0, Math.min(8, rawKey.length()));
    }
}
