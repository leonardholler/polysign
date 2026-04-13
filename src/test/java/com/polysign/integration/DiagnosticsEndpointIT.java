package com.polysign.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@code /actuator/diagnostics/**} endpoints.
 *
 * <p>Runs the full application context (via {@link AbstractIntegrationIT} +
 * LocalStack) to verify that Spring Boot's actuator handler mapping does NOT
 * shadow the plain {@code @RestController} registered at {@code /actuator/diagnostics/**},
 * and that both endpoints return HTTP 200 with the expected JSON structure.
 */
@AutoConfigureMockMvc
class DiagnosticsEndpointIT extends AbstractIntegrationIT {

    @Autowired
    MockMvc mockMvc;

    @Test
    void resolutionCoverage_returns200() throws Exception {
        mockMvc.perform(get("/actuator/diagnostics/resolution-coverage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").isArray());
    }

    @Test
    void detectorThresholds_returns200WithExpectedStructure() throws Exception {
        mockMvc.perform(get("/actuator/diagnostics/detector-thresholds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceMovement").exists())
                .andExpect(jsonPath("$.priceMovement.thresholdTier1Pct").isNumber())
                .andExpect(jsonPath("$.statisticalAnomaly").exists())
                .andExpect(jsonPath("$.statisticalAnomaly.zScoreTier1").isNumber())
                .andExpect(jsonPath("$.whale").exists())
                .andExpect(jsonPath("$.whale.minTradeUsdcThreshold").isNumber())
                .andExpect(jsonPath("$.combinedFilterCountsLastHour").exists());
    }
}
