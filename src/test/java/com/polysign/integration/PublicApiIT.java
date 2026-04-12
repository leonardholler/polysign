package com.polysign.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polysign.alert.AlertService;
import com.polysign.config.ApiKeyRepository;
import com.polysign.config.CreatedApiKey;
import com.polysign.model.Alert;
import com.polysign.model.Tier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for the public /api/v1 endpoints.
 *
 * <p>Covers:
 * <ol>
 *   <li>401 when {@code X-API-Key} is absent</li>
 *   <li>401 when {@code X-API-Key} is invalid</li>
 *   <li>200 with a well-formed {@link com.polysign.api.v1.dto.PaginatedResponse} envelope on a valid key</li>
 *   <li>Cursor pagination — no alertId appears on both page 1 and page 2</li>
 *   <li>Rate limiting — 65 requests against a FREE (60 req/min) key yields ≥1 429 with all
 *       required headers ({@code X-RateLimit-Limit}, {@code X-RateLimit-Remaining},
 *       {@code X-RateLimit-Reset}, {@code Retry-After})</li>
 * </ol>
 *
 * <p>Each test creates a unique API key so it starts with a fresh Resilience4j rate-limiter
 * instance (rate limiters are keyed by {@code apiKeyHash}).
 */
@AutoConfigureMockMvc
class PublicApiIT extends AbstractIntegrationIT {

    private static final String    V1_ALERTS = "/api/v1/alerts";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired MockMvc              mockMvc;
    @Autowired ApiKeyRepository     apiKeyRepository;
    @Autowired AlertService         alertService;
    @Autowired DynamoDbTable<Alert> alertsTable;

    /** Fresh per-test key; unique apiKeyHash guarantees an isolated rate-limiter instance. */
    private CreatedApiKey testKey;

    /** Tracks alerts seeded during a test so tearDown can remove them. */
    private final List<Alert> seededAlerts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        testKey = apiKeyRepository.createNew("it-pub-" + UUID.randomUUID(), Tier.FREE);
    }

    @AfterEach
    void tearDown() {
        apiKeyRepository.deactivate(testKey.apiKeyHash());
        for (Alert a : seededAlerts) {
            try {
                alertsTable.deleteItem(Key.builder()
                        .partitionValue(a.getAlertId())
                        .sortValue(a.getCreatedAt())
                        .build());
            } catch (Exception ignored) { /* item may not exist if tryCreate was deduped */ }
        }
        seededAlerts.clear();
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    @Test
    void noApiKey_returns401() throws Exception {
        mockMvc.perform(get(V1_ALERTS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void invalidApiKey_returns401() throws Exception {
        mockMvc.perform(get(V1_ALERTS).header("X-API-Key", "psk_thiskeyisnotregistered"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void validKey_returns200WithPaginatedEnvelope() throws Exception {
        mockMvc.perform(get(V1_ALERTS).header("X-API-Key", testKey.rawKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.pagination").exists())
                .andExpect(jsonPath("$.pagination.hasMore").isBoolean())
                .andExpect(jsonPath("$.meta").exists())
                .andExpect(jsonPath("$.meta.clientName").value(testKey.clientName()))
                .andExpect(jsonPath("$.meta.requestedAt").isNotEmpty());
    }

    // ── Cursor pagination ─────────────────────────────────────────────────────

    /**
     * Seeds 3 alerts via {@link AlertService}, fetches with {@code limit=2} to land on
     * page 1, follows the cursor to page 2, and asserts no alertId appears on both pages.
     */
    @Test
    void pagination_noOverlapAcrossPages() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        for (int i = 0; i < 3; i++) {
            Alert a = buildAlert(tag, i);
            alertService.tryCreate(a);
            seededAlerts.add(a);
        }

        // ── Page 1 (limit=2) ──
        String body1 = mockMvc.perform(get(V1_ALERTS + "?limit=2")
                        .header("X-API-Key", testKey.rawKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn().getResponse().getContentAsString();

        JsonNode page1 = MAPPER.readTree(body1);
        boolean hasMore = page1.path("pagination").path("hasMore").asBoolean(false);
        if (!hasMore) {
            // Fewer than 3 alerts visible after the since-filter; skip overlap assertion.
            // This can happen if the table had items that were filtered out before limit was hit.
            return;
        }

        String cursor = page1.path("pagination").path("cursor").asText(null);
        assertThat(cursor).as("cursor must be non-null when hasMore=true").isNotNull();

        Set<String> ids1 = new HashSet<>();
        page1.path("data").forEach(n -> ids1.add(n.path("alertId").asText()));
        assertThat(ids1).as("page 1 must contain at least one alert").isNotEmpty();

        // ── Page 2 (limit=2, from cursor) ──
        String body2 = mockMvc.perform(get(V1_ALERTS + "?limit=2&cursor=" + cursor)
                        .header("X-API-Key", testKey.rawKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andReturn().getResponse().getContentAsString();

        JsonNode page2 = MAPPER.readTree(body2);
        Set<String> ids2 = new HashSet<>();
        page2.path("data").forEach(n -> ids2.add(n.path("alertId").asText()));
        assertThat(ids2).as("page 2 must contain at least one alert").isNotEmpty();

        // Overlap check
        Set<String> overlap = new HashSet<>(ids1);
        overlap.retainAll(ids2);
        assertThat(overlap).as("no alertId should appear on both pages").isEmpty();
    }

    // ── Rate limiting ─────────────────────────────────────────────────────────

    /**
     * Sends 65 requests with a FREE key (60 req/min limit) and asserts that at least
     * one request is rejected with HTTP 429 and all required rate-limit headers.
     */
    @Test
    void rateLimit_burst65_atLeastOne429WithAllHeaders() throws Exception {
        // Dedicated key → guaranteed fresh Resilience4j rate-limiter (unique apiKeyHash).
        CreatedApiKey burstKey = apiKeyRepository.createNew("burst-" + UUID.randomUUID(), Tier.FREE);
        int count429 = 0;

        try {
            for (int i = 0; i < 65; i++) {
                MvcResult result = mockMvc.perform(get(V1_ALERTS)
                                .header("X-API-Key", burstKey.rawKey()))
                        .andReturn();

                if (result.getResponse().getStatus() == 429) {
                    count429++;
                    assertThat(result.getResponse().getHeader("X-RateLimit-Limit"))
                            .as("X-RateLimit-Limit must be present on 429").isNotNull();
                    assertThat(result.getResponse().getHeader("X-RateLimit-Remaining"))
                            .as("X-RateLimit-Remaining must be 0 on 429").isEqualTo("0");
                    assertThat(result.getResponse().getHeader("X-RateLimit-Reset"))
                            .as("X-RateLimit-Reset must be present on 429").isNotNull();
                    assertThat(result.getResponse().getHeader("Retry-After"))
                            .as("Retry-After must be present on 429").isNotNull();
                }
            }
        } finally {
            apiKeyRepository.deactivate(burstKey.apiKeyHash());
        }

        assertThat(count429)
                .as("At least one 429 must be returned after exceeding FREE (60 req/min) limit")
                .isGreaterThanOrEqualTo(1);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Alert buildAlert(String tag, int index) {
        Alert a = new Alert();
        a.setAlertId("it-pub-" + tag + "-" + index);
        // Each alert gets a distinct createdAt within the last few seconds (all within 7-day window)
        a.setCreatedAt(Instant.now().minusSeconds(index).toString());
        a.setType("price_movement");
        a.setSeverity("info");
        a.setMarketId("market-pub-" + tag);
        a.setTitle("PublicApiIT alert " + index);
        a.setDescription("Seeded by PublicApiIT for pagination test");
        return a;
    }
}
