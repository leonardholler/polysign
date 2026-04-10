package com.polysign.notification;

import com.polysign.backtest.SignalPerformanceService;
import com.polysign.backtest.SignalPerformanceService.DetectorPerformance;
import com.polysign.backtest.SignalPerformanceService.PerformanceResponse;
import com.polysign.common.AppClock;
import com.polysign.model.Alert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PhoneWorthinessFilter}.
 *
 * Uses a testable subclass that overrides {@code queryRecentAlertsForMarket} so
 * tests run without a real DynamoDB connection. The six cases cover each rule branch.
 */
@ExtendWith(MockitoExtension.class)
class PhoneWorthinessFilterTest {

    @Mock DynamoDbTable<Alert>   alertsTable;
    @Mock SignalPerformanceService performanceService;
    @Mock AppClock               clock;

    TestableFilter filter;

    // ── Testable subclass ─────────────────────────────────────────────────────

    static class TestableFilter extends PhoneWorthinessFilter {
        private List<Alert> stubbedAlerts = List.of();

        TestableFilter(DynamoDbTable<Alert> table,
                       SignalPerformanceService svc,
                       AppClock clock) {
            super(table, svc, clock, 0.60);
        }

        void stubRecentAlerts(List<Alert> alerts) { this.stubbedAlerts = alerts; }

        @Override
        List<Alert> queryRecentAlertsForMarket(String marketId) { return stubbedAlerts; }
    }

    @BeforeEach
    void setUp() {
        filter = new TestableFilter(alertsTable, performanceService, clock);
        // lenient — only consumed by tests that reach rule (c)
        lenient().when(clock.now()).thenReturn(Instant.parse("2026-04-09T10:00:00Z"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Alert alert(String type, String severity) {
        Alert a = new Alert();
        a.setAlertId("test-" + type);
        a.setType(type);
        a.setSeverity(severity);
        a.setMarketId("market-001");
        a.setCreatedAt("2026-04-09T09:50:00Z");
        return a;
    }

    private PerformanceResponse perfResponse(String type, Double precision) {
        DetectorPerformance dp = new DetectorPerformance(type, 10, precision, 0.01, 0.01, 0.01);
        return new PerformanceResponse("t1h", "2026-04-02T00:00:00Z", List.of(dp));
    }

    // ── Test 1 ────────────────────────────────────────────────────────────────

    /**
     * Rule (a): consensus always returns true regardless of severity.
     */
    @Test
    void consensusAlertAlwaysPasses() {
        Alert a = alert("consensus", "critical");
        assertThat(filter.isPhoneWorthy(a)).isTrue();
    }

    // ── Test 2 ────────────────────────────────────────────────────────────────

    /**
     * A lone price_movement alert with no other detector types on the same market
     * and severity="warning" (not critical) should return false.
     *
     * Rule (a): fails — not consensus.
     * Rule (b): fails — only one distinct type in the stub.
     * Rule (c): not evaluated — severity is "warning".
     */
    @Test
    void lonePriceMovementBlocked() {
        Alert a = alert("price_movement", "warning");
        filter.stubRecentAlerts(List.of(a)); // only one type in window
        assertThat(filter.isPhoneWorthy(a)).isFalse();
    }

    // ── Test 3 ────────────────────────────────────────────────────────────────

    /**
     * Two different detector types fired on the same market within the 15-min window.
     * Rule (b) should fire and return true.
     */
    @Test
    void multiDetectorConvergencePasses() {
        Alert pm = alert("price_movement",      "warning");
        Alert sa = alert("statistical_anomaly", "warning");
        filter.stubRecentAlerts(List.of(pm, sa)); // two distinct types
        assertThat(filter.isPhoneWorthy(pm)).isTrue();
    }

    // ── Test 4 ────────────────────────────────────────────────────────────────

    /**
     * Critical severity + t1h precision = 0.65 ≥ 0.60 → rule (c) returns true.
     */
    @Test
    void criticalWithHighPrecisionPasses() {
        Alert a = alert("price_movement", "critical");
        filter.stubRecentAlerts(List.of(a)); // rule (b) fails — only one type
        when(performanceService.getPerformance(eq("price_movement"), eq("t1h"), any()))
                .thenReturn(perfResponse("price_movement", 0.65));

        assertThat(filter.isPhoneWorthy(a)).isTrue();
    }

    // ── Test 5 ────────────────────────────────────────────────────────────────

    /**
     * Critical severity + t1h precision = 0.50 < 0.60 → rule (c) returns false.
     */
    @Test
    void criticalWithLowPrecisionBlocked() {
        Alert a = alert("price_movement", "critical");
        filter.stubRecentAlerts(List.of(a));
        when(performanceService.getPerformance(eq("price_movement"), eq("t1h"), any()))
                .thenReturn(perfResponse("price_movement", 0.50));

        assertThat(filter.isPhoneWorthy(a)).isFalse();
    }

    // ── Test 6 ────────────────────────────────────────────────────────────────

    /**
     * Critical severity + precision = null (no outcome data yet) → fails closed → false.
     */
    @Test
    void criticalWithNoPrecisionDataBlocked() {
        Alert a = alert("price_movement", "critical");
        filter.stubRecentAlerts(List.of(a));
        when(performanceService.getPerformance(eq("price_movement"), eq("t1h"), any()))
                .thenReturn(perfResponse("price_movement", null));

        assertThat(filter.isPhoneWorthy(a)).isFalse();
    }
}
