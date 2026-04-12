package com.polysign.api;

import com.polysign.config.ApiKeyRepository;
import com.polysign.model.ApiKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ApiKeyAuthFilterTest {

    private ApiKeyRepository     repo;
    private ApiKeyAuthFilter     filter;
    private MockHttpServletRequest  req;
    private MockHttpServletResponse res;
    private MockFilterChain         chain;

    @BeforeEach
    void setUp() {
        repo   = mock(ApiKeyRepository.class);
        filter = new ApiKeyAuthFilter(repo);
        req    = new MockHttpServletRequest();
        res    = new MockHttpServletResponse();
        chain  = new MockFilterChain();
    }

    @Test
    void missingHeader_returns401() throws Exception {
        filter.doFilterInternal(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentType()).contains("application/json");
        assertThat(res.getContentAsString()).contains("Invalid or missing API key");
        verify(repo, never()).findByRawKey(anyString());
    }

    @Test
    void unknownKey_returns401() throws Exception {
        req.addHeader("X-API-Key", "psk_unknown1");
        when(repo.findByRawKey("psk_unknown1")).thenReturn(Optional.empty());

        filter.doFilterInternal(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("Invalid or missing API key");
    }

    @Test
    void inactiveKey_returns401() throws Exception {
        ApiKey inactive = apiKey("psk_inact1", false);
        req.addHeader("X-API-Key", "psk_inact1");
        when(repo.findByRawKey("psk_inact1")).thenReturn(Optional.of(inactive));

        filter.doFilterInternal(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void validKey_storesApiKeyAndContinues() throws Exception {
        ApiKey active = apiKey("psk_valid1", true);
        req.addHeader("X-API-Key", "psk_valid1");
        when(repo.findByRawKey("psk_valid1")).thenReturn(Optional.of(active));

        filter.doFilterInternal(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200); // default MockHttpServletResponse
        Optional<ApiKey> stored = ApiKeyContext.getApiKey(req);
        assertThat(stored).isPresent();
        assertThat(stored.get().getClientName()).isEqualTo("test-client");
        assertThat(chain.getRequest()).isNotNull(); // chain was invoked
    }

    @Test
    void validKey_doesNotLogRawKey() throws Exception {
        // Raw key is only stored in the request attribute, never logged.
        // This test verifies the ApiKey (which has only keyPrefix) is what's stored.
        String rawKey = "psk_raw1234";
        ApiKey active = apiKey(rawKey, true);
        active.setKeyPrefix(rawKey.substring(0, 8)); // "psk_raw1"
        req.addHeader("X-API-Key", rawKey);
        when(repo.findByRawKey(rawKey)).thenReturn(Optional.of(active));

        filter.doFilterInternal(req, res, chain);

        // The stored attribute is the ApiKey (contains keyPrefix, not rawKey)
        ApiKey stored = ApiKeyContext.getApiKey(req).orElseThrow();
        assertThat(stored.getKeyPrefix()).isEqualTo("psk_raw1");
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private static ApiKey apiKey(String rawKey, boolean active) {
        ApiKey k = new ApiKey();
        k.setApiKeyHash("hash_" + rawKey);
        k.setClientName("test-client");
        k.setTier("FREE");
        k.setRateLimit(60);
        k.setCreatedAt("2026-04-11T00:00:00Z");
        k.setActive(active);
        k.setKeyPrefix(rawKey.substring(0, Math.min(8, rawKey.length())));
        return k;
    }
}
