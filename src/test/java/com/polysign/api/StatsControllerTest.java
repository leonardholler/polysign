package com.polysign.api;

import com.polysign.backtest.SignalPerformanceService;
import com.polysign.backtest.SignalPerformanceService.AggregatePrecision;
import com.polysign.common.AppClock;
import com.polysign.common.AppStats;
import com.polysign.model.Alert;
import com.polysign.model.AlertOutcome;
import com.polysign.model.Market;
import com.polysign.model.WatchedWallet;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StatsController}.
 *
 * Verifies precision fields, resolution zone counts, and the 10-second response cache.
 */
@ExtendWith(MockitoExtension.class)
class StatsControllerTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    DynamoDbTable<Alert> alertsTable;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    DynamoDbTable<WatchedWallet> watchedWalletsTable;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    DynamoDbTable<Market> marketsTable;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    DynamoDbTable<AlertOutcome> alertOutcomesTable;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    MeterRegistry meterRegistry;

    @Mock
    AppStats appStats;

    @Mock
    SignalPerformanceService signalPerformanceService;

    AppClock clock;
    StatsController controller;

    /**
     * Helper: wraps a list as a typed {@link SdkIterable} for Mockito stubs.
     * {@code SdkIterable} is a functional interface (iterator() is the only abstract method).
     */
    @SafeVarargs
    private static <T> SdkIterable<T> iterableOf(T... items) {
        List<T> list = Arrays.asList(items);
        return list::iterator;
    }

    @BeforeEach
    void setUp() {
        clock = new AppClock();
        clock.setClock(Clock.fixed(Instant.parse("2026-04-09T12:00:00Z"), ZoneOffset.UTC));

        // alertsTable is iterated with a for-each in the controller
        when(alertsTable.scan().items()).thenReturn(iterableOf());
        when(watchedWalletsTable.scan().items().stream()).thenReturn(Stream.of());
        when(marketsTable.scan().items().stream()).thenReturn(Stream.of());
        // alertOutcomesTable uses ScanEnhancedRequest — disambiguate overload
        when(alertOutcomesTable.scan(any(ScanEnhancedRequest.class)).items().stream())
                .thenReturn(Stream.of());

        // stub MeterRegistry gauge lookup to return null (no gauge registered)
        when(meterRegistry.find(any()).gauge()).thenReturn(null);

        // Fix 1: walletsSeenInLast24h comes from appStats, not a DB scan.
        // lenient — some tests override this stub with a specific return value.
        lenient().when(appStats.walletsSeenInLast24h(any(Clock.class))).thenReturn(0L);

        // stub getPerformance for insider_signature to return empty result
        when(signalPerformanceService.getPerformance(any(), any(), any()))
                .thenReturn(new SignalPerformanceService.PerformanceResponse("t1h", "", List.of()));

        controller = new StatsController(
                alertsTable, watchedWalletsTable, marketsTable, alertOutcomesTable,
                meterRegistry, appStats, clock, "test-topic", signalPerformanceService);
    }

    // ── Test 1: precision fields populated when scored samples exist ──────────

    @Test
    void precisionFieldsPopulated_whenScoredSamplesExist() {
        when(signalPerformanceService.getAggregatePrecision(eq("t1h"), any()))
                .thenReturn(new AggregatePrecision(0.583, 24L));
        when(signalPerformanceService.getAggregatePrecision(eq("t15m"), any()))
                .thenReturn(new AggregatePrecision(0.612, 30L));

        StatsController.StatsResponse resp = controller.getStats();

        assertThat(resp.signalPrecision7d1h()).isNotNull();
        assertThat(resp.signalPrecision7d1h()).isCloseTo(0.583, within(1e-9));
        assertThat(resp.scoredSamples7d1h()).isEqualTo(24L);

        assertThat(resp.signalPrecision7d15m()).isNotNull();
        assertThat(resp.signalPrecision7d15m()).isCloseTo(0.612, within(1e-9));
        assertThat(resp.scoredSamples7d15m()).isEqualTo(30L);
    }

    // ── Test 2: precision null + samples 0 when scorer table is empty ─────────

    @Test
    void precisionNull_andSamplesZero_whenNoScoredOutcomes() {
        when(signalPerformanceService.getAggregatePrecision(any(), any()))
                .thenReturn(new AggregatePrecision(null, 0L));

        StatsController.StatsResponse resp = controller.getStats();

        assertThat(resp.signalPrecision7d1h()).isNull();
        assertThat(resp.scoredSamples7d1h()).isEqualTo(0L);

        assertThat(resp.signalPrecision7d15m()).isNull();
        assertThat(resp.scoredSamples7d15m()).isEqualTo(0L);
    }

    // ── Test 3: resolution zone fields reflect market and outcome counts ───────

    @Test
    void resolutionZoneFields_reflectCountsFromTables() {
        when(signalPerformanceService.getAggregatePrecision(any(), any()))
                .thenReturn(new AggregatePrecision(null, 0L));

        // One effectively-resolved market (resolvedBy set + YES price >= 0.99)
        Market resolved = new Market();
        resolved.setMarketId("mkt-resolved-01");
        resolved.setResolvedBy("0xUMA");
        resolved.setOutcomePrices(List.of("0.99", "0.01"));

        // One non-resolved market
        Market open = new Market();
        open.setMarketId("mkt-open-01");

        when(marketsTable.scan().items().stream()).thenReturn(Stream.of(resolved, open));

        // Two resolution outcome rows + one non-resolution row
        AlertOutcome res1 = new AlertOutcome();
        res1.setAlertId("alert-01");
        res1.setHorizon("resolution");

        AlertOutcome res2 = new AlertOutcome();
        res2.setAlertId("alert-02");
        res2.setHorizon("resolution");

        AlertOutcome t1h = new AlertOutcome();
        t1h.setAlertId("alert-03");
        t1h.setHorizon("t1h");

        when(alertOutcomesTable.scan(any(ScanEnhancedRequest.class)).items().stream())
                .thenReturn(Stream.of(res1, res2, t1h));

        StatsController.StatsResponse resp = controller.getStats();

        assertThat(resp.marketsInResolutionZone()).isEqualTo(1L);
        assertThat(resp.alertsInResolutionZone()).isEqualTo(2L);
    }

    // ── Test 4: resolution accuracy fields ────────────────────────────────────

    @Test
    void resolutionAccuracy_computedCorrectly() {
        when(signalPerformanceService.getAggregatePrecision(any(), any()))
                .thenReturn(new AggregatePrecision(null, 0L));
        when(marketsTable.scan().items().stream()).thenReturn(Stream.of());

        AlertOutcome correct1 = resolutionOutcome("a1", "up",   "up",   1.0);
        AlertOutcome correct2 = resolutionOutcome("a2", "down", "down", 0.0);
        AlertOutcome correct3 = resolutionOutcome("a3", "up",   "up",   1.0);
        AlertOutcome wrong1   = resolutionOutcome("a4", "up",   "down", 0.0);
        AlertOutcome other    = resolutionOutcome("a5", "up",   "up",   1.0);
        other.setHorizon("t1h");

        when(alertOutcomesTable.scan(any(ScanEnhancedRequest.class)).items().stream())
                .thenReturn(Stream.of(correct1, correct2, correct3, wrong1, other));

        StatsController.StatsResponse resp = controller.getStats();

        assertThat(resp.alertsInResolutionZone()).isEqualTo(4L);
        assertThat(resp.resolutionCorrect()).isEqualTo(3L);
        assertThat(resp.resolutionAccuracyPct()).isEqualTo(75.0);
    }

    @Test
    void resolutionAccuracy_nullWhenNoResolutionOutcomes() {
        when(signalPerformanceService.getAggregatePrecision(any(), any()))
                .thenReturn(new AggregatePrecision(null, 0L));
        when(marketsTable.scan().items().stream()).thenReturn(Stream.of());

        StatsController.StatsResponse resp = controller.getStats();

        assertThat(resp.resolutionCorrect()).isEqualTo(0L);
        assertThat(resp.resolutionAccuracyPct()).isNull();
    }

    @Test
    void resolutionAccuracy_notCountedWhenPriceNotFullyResolved() {
        when(signalPerformanceService.getAggregatePrecision(any(), any()))
                .thenReturn(new AggregatePrecision(null, 0L));
        when(marketsTable.scan().items().stream()).thenReturn(Stream.of());

        AlertOutcome stuck = resolutionOutcome("a1", "up", "up", 0.50);

        when(alertOutcomesTable.scan(any(ScanEnhancedRequest.class)).items().stream())
                .thenReturn(Stream.of(stuck));

        StatsController.StatsResponse resp = controller.getStats();

        assertThat(resp.alertsInResolutionZone()).isEqualTo(1L);
        assertThat(resp.resolutionCorrect()).isEqualTo(0L);
        assertThat(resp.resolutionAccuracyPct()).isEqualTo(0.0);
    }

    // ── Test 5: Fix 1 — walletsSeenToday comes from appStats, not a DB scan ───

    @Test
    void walletsSeenToday_delegatesToAppStats() {
        when(signalPerformanceService.getAggregatePrecision(any(), any()))
                .thenReturn(new AggregatePrecision(null, 0L));
        when(appStats.walletsSeenInLast24h(any(Clock.class))).thenReturn(42L);

        StatsController.StatsResponse resp = controller.getStats();

        assertThat(resp.walletsSeenToday()).isEqualTo(42L);
    }

    // ── Test 6: Fix 3 — 10-second response cache ─────────────────────────────

    @Test
    void getStats_returnsCachedResponse_withinTtl() {
        when(signalPerformanceService.getAggregatePrecision(any(), any()))
                .thenReturn(new AggregatePrecision(null, 0L));

        // Both calls use the same fixed clock instant → same millis → cache hit
        StatsController.StatsResponse first  = controller.getStats();
        StatsController.StatsResponse second = controller.getStats();

        assertThat(second).isSameAs(first); // same object reference — cache hit
    }

    @Test
    void getStats_recomputesAfterCacheTtlExpires() {
        when(signalPerformanceService.getAggregatePrecision(any(), any()))
                .thenReturn(new AggregatePrecision(null, 0L));

        StatsController.StatsResponse first = controller.getStats();

        // Advance clock by 11 seconds — past the 10s TTL
        clock.setClock(Clock.fixed(Instant.parse("2026-04-09T12:00:11Z"), ZoneOffset.UTC));
        // Re-stub all stream/iterable returns — Streams are single-use; the first getStats() consumed them
        when(alertsTable.scan().items()).thenReturn(iterableOf());
        when(watchedWalletsTable.scan().items().stream()).thenReturn(Stream.of());
        when(alertOutcomesTable.scan(any(ScanEnhancedRequest.class)).items().stream())
                .thenReturn(Stream.of());
        when(marketsTable.scan().items().stream()).thenReturn(Stream.of());

        StatsController.StatsResponse second = controller.getStats();

        assertThat(second).isNotSameAs(first); // new object — cache miss
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AlertOutcome resolutionOutcome(
            String alertId, String predicted, String realized, double priceAtHorizon) {
        AlertOutcome o = new AlertOutcome();
        o.setAlertId(alertId);
        o.setHorizon("resolution");
        o.setDirectionPredicted(predicted);
        o.setDirectionRealized(realized);
        o.setPriceAtHorizon(java.math.BigDecimal.valueOf(priceAtHorizon));
        return o;
    }
}
