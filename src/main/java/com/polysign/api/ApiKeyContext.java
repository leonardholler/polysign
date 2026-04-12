package com.polysign.api;

import com.polysign.model.ApiKey;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/**
 * Static accessor for the authenticated {@link ApiKey} stored in the current request's
 * attributes by {@link ApiKeyAuthFilter}.
 *
 * <p>Use this in v1 controllers to access the caller's identity (clientName, tier) without
 * passing the request object through multiple layers.
 */
public final class ApiKeyContext {

    static final String ATTR = "polysign.apiKey";

    private ApiKeyContext() {}

    /** Returns the authenticated API key for this request, or empty if not authenticated. */
    public static Optional<ApiKey> getApiKey(HttpServletRequest request) {
        return Optional.ofNullable((ApiKey) request.getAttribute(ATTR));
    }

    static void setApiKey(HttpServletRequest request, ApiKey apiKey) {
        request.setAttribute(ATTR, apiKey);
    }
}
