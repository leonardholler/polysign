package com.polysign.api;

import com.polysign.backtest.SignalPerformanceService;
import com.polysign.backtest.SignalPerformanceService.DetectorPerformance;
import com.polysign.backtest.SignalPerformanceService.PerformanceResponse;
import com.polysign.common.AppClock;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MockMvc controller test for {@link SignalPerformanceController}.
 *
 * Verifies:
 * 1. GET /api/signals/performance → 200 with valid JSON shape
 * 2. GET /api/signals/performance?horizon=invalid → 400, RFC 7807
 */
@WebMvcTest(controllers = {SignalPerformanceController.class, GlobalExceptionHandler.class})
class SignalPerformanceControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SignalPerformanceService service;

    @Autowired
    AppClock clock;

    // ── Test 1: happy path ────────────────────────────────────────────────────

    @Test
    void getPerformance_validRequest_returns200WithCorrectShape() throws Exception {
        PerformanceResponse stubResponse = new PerformanceResponse(
                "t1h",
                "2026-04-02T12:00:00Z",
                List.of(new DetectorPerformance("price_movement", 42, 0.58, 0.021, 0.014, 0.033, 42, 0, null, null)),
                0, 0);

        when(service.getPerformance(any(), any(), any())).thenReturn(stubResponse);

        mvc.perform(get("/api/signals/performance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.horizon").value("t1h"))
                .andExpect(jsonPath("$.since").value("2026-04-02T12:00:00Z"))
                .andExpect(jsonPath("$.detectors[0].type").value("price_movement"))
                .andExpect(jsonPath("$.detectors[0].count").value(42))
                .andExpect(jsonPath("$.detectors[0].precision").value(0.58));
    }

    // ── Test 2: invalid horizon → 400 RFC 7807 ───────────────────────────────

    @Test
    void getPerformance_invalidHorizon_returns400ProblemJson() throws Exception {
        mvc.perform(get("/api/signals/performance?horizon=invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"));
    }

    // ── Test 3: invalid type → 400 ────────────────────────────────────────────

    @Test
    void getPerformance_invalidType_returns400() throws Exception {
        mvc.perform(get("/api/signals/performance?type=unknown_detector"))
                .andExpect(status().isBadRequest());
    }

    // ── Test 4: invalid since → 400 ──────────────────────────────────────────

    @Test
    void getPerformance_invalidSince_returns400() throws Exception {
        mvc.perform(get("/api/signals/performance?since=not-a-date"))
                .andExpect(status().isBadRequest());
    }

    // ── Spring configuration for the test slice ───────────────────────────────

    @TestConfiguration
    static class TestConfig {

        @Bean
        SignalPerformanceService signalPerformanceService() {
            return Mockito.mock(SignalPerformanceService.class);
        }

        @Bean
        AppClock appClock() {
            AppClock c = new AppClock();
            c.setClock(Clock.fixed(Instant.parse("2026-04-09T12:00:00Z"), ZoneOffset.UTC));
            return c;
        }
    }
}
