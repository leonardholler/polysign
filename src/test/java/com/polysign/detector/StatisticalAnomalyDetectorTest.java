package com.polysign.detector;

import com.polysign.alert.AlertService;
import com.polysign.common.AppClock;
import com.polysign.model.Alert;
import com.polysign.model.Market;
import com.polysign.model.PriceSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StatisticalAnomalyDetector}.
 *
 * <p>All tests inject synthetic price snapshot series via a
 * {@link TestableDetector} subclass that overrides {@code querySnapshots()}.
 * The detector computes rolling 1-minute returns, then z-scores the most
 * recent return against the series statistics. An alert fires only when:
 * <ul>
 *   <li>z-score &ge; 3.0 (configurable threshold)</li>
 *   <li>window has &ge; 20 snapshots</li>
 *   <li>24h volume &ge; $50,000</li>
 *   <li>absolute price delta &ge; 0.03 (delta-p floor)</li>
 *   <li>not in extreme zone (both prices &lt; 0.05 or both &gt; 0.95)</li>
 * </ul>
 */
class StatisticalAnomalyDetectorTest {

    private static final Instant NOW = Instant.parse("2026-04-09T12:00:00Z");
    private static final double Z_SCORE_THRESHOLD = 3.0;
    private static final int MIN_SNAPSHOTS = 20;
    private static final double MIN_VOLUME = 50_000.0;
    private static final double MIN_DELTA_P = 0.03;
    private static final int DEDUPE_WINDOW_MINUTES = 30;

    private AlertService alertService;

    @BeforeEach
    void setUp() {
        alertService = mock(AlertService.class);
        when(alertService.tryCreate(any())).thenReturn(true);
    }

    // ── Helper builders ─────────────────────────────────────────────────────

    private static Market market(String id, String volume24h) {
        Market m = new Market();
        m.setMarketId(id);
        m.setQuestion("Will X happen?");
        m.setVolume24h(volume24h);
        m.setIsWatched(false);
        return m;
    }

    private static PriceSnapshot snap(String marketId, Instant time, double midpoint) {
        PriceSnapshot s = new PriceSnapshot();
        s.setMarketId(marketId);
        s.setTimestamp(time.toString());
        s.setMidpoint(BigDecimal.valueOf(midpoint));
        s.setYesPrice(BigDecimal.valueOf(midpoint));
        s.setNoPrice(BigDecimal.ONE.subtract(BigDecimal.valueOf(midpoint)));
        return s;
    }

    /**
     * Build a series of snapshots at 1-minute intervals ending at NOW.
     * Prices are given oldest-first; the last element corresponds to NOW.
     */
    private static List<PriceSnapshot> series(String marketId, double... prices) {
        List<PriceSnapshot> snaps = new ArrayList<>();
        int n = prices.length;
        for (int i = 0; i < n; i++) {
            Instant t = NOW.minus(Duration.ofMinutes(n - 1 - i));
            snaps.add(snap(marketId, t, prices[i]));
        }
        return snaps;
    }

    // ── Test 1: Flat series (price constant) → no alert ─────────────────────
    //
    // 30 snapshots at exactly 0.50. All 1-minute returns are 0.
    // stddev = 0, so the detector must skip (no volatility info).

    @Test
    void flatSeriesProducesNoAlert() {
        double[] prices = new double[30];
        for (int i = 0; i < 30; i++) prices[i] = 0.50;

        verifyNoAlert(market("m1", "100000"), series("m1", prices));
    }

    // ── Test 2: Linear trend (slow drift) → no alert ────────────────────────
    //
    // 30 snapshots from 0.50 to 0.5145 in steps of 0.0005.
    // Every 1-minute return is identical (~0.1%), so each return equals
    // the mean. stddev of identical values = 0 → detector skips.
    // (Even with floating-point jitter making stddev nonzero, the last
    // return is the same size as all prior returns → z ≈ 0.)

    @Test
    void linearTrendProducesNoAlert() {
        double[] prices = new double[30];
        for (int i = 0; i < 30; i++) prices[i] = 0.50 + i * 0.0005;

        verifyNoAlert(market("m1", "100000"), series("m1", prices));
    }

    // ── Test 3: Random walk with small noise → no alert ─────────────────────
    //
    // 30 snapshots: base 0.50, alternating ±0.002 (0.4% moves).
    // All returns are roughly equal in magnitude. The final return is
    // the same scale as prior returns, so z-score ≈ 0.

    @Test
    void smallNoiseRandomWalkProducesNoAlert() {
        double[] prices = new double[30];
        for (int i = 0; i < 30; i++) {
            prices[i] = 0.50 + (i % 2 == 0 ? 0.002 : -0.002);
        }

        verifyNoAlert(market("m1", "100000"), series("m1", prices));
    }

    // ── Test 4: Sudden 3.5σ spike on otherwise flat-ish series → alert ──────
    //
    // 29 snapshots at 0.50 ± tiny ε-noise (±0.0001), then a final spike.
    // The ε-noise gives a nonzero stddev while keeping the mean return ≈ 0.
    //
    // Returns from ε-noise: magnitude ≈ 0.0002 each way, alternating.
    // Approximate stddev of these returns ≈ 0.0002.
    // For a 3.5σ spike: last return needs to be ≈ 3.5 × 0.0002 = 0.0007.
    // But we also need |delta-p| ≥ 0.03 for the delta-p floor.
    //
    // Strategy: use 29 snapshots with ε-noise at base 0.50, then spike
    // to 0.54 (delta = 0.04 > 0.03 floor, and the return is enormous
    // relative to the ε-noise stddev → well above 3.5σ).

    @Test
    void sudden3point5SigmaSpikeFiresAlert() {
        double[] prices = new double[30];
        for (int i = 0; i < 29; i++) {
            // Alternating ±0.0001 around 0.50 to produce nonzero stddev
            prices[i] = 0.50 + (i % 2 == 0 ? 0.0001 : -0.0001);
        }
        // Final spike: 0.50 → 0.54 = 8% move, delta = 0.04 > floor,
        // return ≈ 0.08 vs stddev ≈ 0.0004 → z ≈ 200 (well above 3.5)
        prices[29] = 0.54;

        Market m = market("m1", "100000");
        AlertService spy = mock(AlertService.class);
        when(spy.tryCreate(any())).thenReturn(true);

        TestableDetector det = detector(spy, series("m1", prices));
        det.checkMarket(m, NOW);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(spy).tryCreate(captor.capture());
        Alert alert = captor.getValue();

        assertThat(alert.getType()).isEqualTo("statistical_anomaly");
        assertThat(alert.getSeverity()).isEqualTo("warning");
        assertThat(alert.getMarketId()).isEqualTo("m1");
        assertThat(alert.getMetadata()).containsKey("zScore");
        assertThat(Double.parseDouble(alert.getMetadata().get("zScore")))
                .isGreaterThanOrEqualTo(3.5);
    }

    // ── Test 5: Sudden 5σ spike → alert ─────────────────────────────────────
    //
    // Same ε-noise base as test 4, but spike to 0.56 (delta = 0.06,
    // return even larger → z well above 5σ).

    @Test
    void sudden5SigmaSpikeFiresAlert() {
        double[] prices = new double[30];
        for (int i = 0; i < 29; i++) {
            prices[i] = 0.50 + (i % 2 == 0 ? 0.0001 : -0.0001);
        }
        prices[29] = 0.56;

        Market m = market("m1", "100000");
        AlertService spy = mock(AlertService.class);
        when(spy.tryCreate(any())).thenReturn(true);

        TestableDetector det = detector(spy, series("m1", prices));
        det.checkMarket(m, NOW);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(spy).tryCreate(captor.capture());
        Alert alert = captor.getValue();

        assertThat(alert.getType()).isEqualTo("statistical_anomaly");
        assertThat(Double.parseDouble(alert.getMetadata().get("zScore")))
                .isGreaterThanOrEqualTo(5.0);
        assertThat(alert.getMetadata()).containsKey("windowSize");
        assertThat(alert.getMetadata()).containsKey("mean");
        assertThat(alert.getMetadata()).containsKey("stddev");
    }

    // ── Test 6: Gradual acceleration (smooth return ramp) → no alert ────────
    //
    // 25 snapshots where each 1-minute return grows linearly:
    //   return[k] = 0.001 * (k+1), k = 0..23
    //   so returns are: 0.001, 0.002, 0.003, ..., 0.024
    //
    // This is a smooth ramp, NOT a step. The stddev of a uniform-ish
    // spread of values 0.001..0.024 is ~0.007. The final return (0.024)
    // is only ~(0.024 - 0.0125) / 0.007 ≈ 1.6σ above the mean —
    // well below the 3.0 threshold.
    //
    // We construct prices so that price[k+1] - price[k] = 0.001*(k+1):
    //   price[0] = 0.40
    //   price[k] = price[0] + sum_{j=1}^{k} 0.001*j = 0.40 + 0.001*k*(k+1)/2

    @Test
    void gradualAccelerationProducesNoAlert() {
        double[] prices = new double[25];
        prices[0] = 0.40;
        for (int k = 1; k < 25; k++) {
            prices[k] = 0.40 + 0.001 * k * (k + 1) / 2.0;
        }
        // price[24] = 0.40 + 0.001 * 24 * 25 / 2 = 0.40 + 0.30 = 0.70
        // Total move: 0.40 → 0.70 but accumulated gradually

        verifyNoAlert(market("m1", "100000"), series("m1", prices));
    }

    // ── Test 7: Window with <20 snapshots → no alert ────────────────────────
    //
    // Only 15 snapshots (below the min-snapshots=20 guard).
    // Even with a massive spike, the detector must refuse to evaluate
    // because there isn't enough history for meaningful statistics.

    @Test
    void insufficientHistoryProducesNoAlert() {
        double[] prices = new double[15];
        for (int i = 0; i < 14; i++) {
            prices[i] = 0.50 + (i % 2 == 0 ? 0.0001 : -0.0001);
        }
        prices[14] = 0.60; // Massive spike — but only 15 snapshots

        verifyNoAlert(market("m1", "100000"), series("m1", prices));
    }

    // ── Test 8: High-volatility market (10% move is NOT anomalous) → no alert
    //
    // 30 snapshots with prior 60min showing wild ±22% alternating swings.
    // The stddev of returns is huge, so a final 10% move (0.50→0.55)
    // is within normal variance — z-score < 3.0.
    //
    // Alternating: 0.50, 0.61, 0.50, 0.61, ... (+22%, -18%, repeating)
    // Returns alternate between ~+0.11 and ~-0.11.
    // Mean return ≈ 0, stddev ≈ 0.11.
    // Final return of +0.05 (10%): z ≈ 0.05 / 0.11 ≈ 0.45 — way below 3.0.

    @Test
    void highVolatilityMarketAbsorbs10PctMove() {
        double[] prices = new double[30];
        for (int i = 0; i < 28; i++) {
            prices[i] = (i % 2 == 0) ? 0.50 : 0.61;
        }
        // End with a "normal-looking" +10% move
        prices[28] = 0.50;
        prices[29] = 0.55; // +10% from 0.50, but stddev is ~0.11

        verifyNoAlert(market("m1", "100000"), series("m1", prices));
    }

    // ── Test 9: Low-volume market with a real anomaly → no alert ─────────────
    //
    // Same spike series as test 4 (would fire at 100k volume), but
    // volume24h is only $10,000 — below the $50,000 floor.

    @Test
    void lowVolumeMarketProducesNoAlert() {
        double[] prices = new double[30];
        for (int i = 0; i < 29; i++) {
            prices[i] = 0.50 + (i % 2 == 0 ? 0.0001 : -0.0001);
        }
        prices[29] = 0.54;

        verifyNoAlert(market("m1", "10000"), series("m1", prices));
    }

    // ── Additional: delta-p floor blocks tail-zone anomaly ───────────────────
    //
    // 30 snapshots near 0.03, ε-noise, spike to 0.035.
    // Delta = 0.005 < 0.03 floor. Both prices < 0.05 (extreme zone).
    // Blocked by both filters.

    @Test
    void tailZoneAnomalyBlockedByDeltaPFloor() {
        double[] prices = new double[30];
        for (int i = 0; i < 29; i++) {
            prices[i] = 0.03 + (i % 2 == 0 ? 0.0001 : -0.0001);
        }
        prices[29] = 0.035;

        verifyNoAlert(market("m1", "100000"), series("m1", prices));
    }

    // ── Additional: upper extreme zone blocked ──────────────────────────────
    //
    // 30 snapshots near 0.97, ε-noise, spike to 0.99.
    // Both prices > 0.95 → extreme-zone filter blocks.

    @Test
    void upperExtremeZoneBlockedEvenIfAnomaly() {
        double[] prices = new double[30];
        for (int i = 0; i < 29; i++) {
            prices[i] = 0.97 + (i % 2 == 0 ? 0.0001 : -0.0001);
        }
        prices[29] = 0.99;

        verifyNoAlert(market("m1", "100000"), series("m1", prices));
    }

    // ── Additional: alert metadata includes expected fields ─────────────────

    @Test
    void alertMetadataContainsZScoreWindowMeanStddev() {
        double[] prices = new double[30];
        for (int i = 0; i < 29; i++) {
            prices[i] = 0.50 + (i % 2 == 0 ? 0.0001 : -0.0001);
        }
        prices[29] = 0.54;

        Market m = market("m1", "100000");
        AlertService spy = mock(AlertService.class);
        when(spy.tryCreate(any())).thenReturn(true);

        TestableDetector det = detector(spy, series("m1", prices));
        det.checkMarket(m, NOW);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(spy).tryCreate(captor.capture());
        Alert alert = captor.getValue();

        assertThat(alert.getMetadata()).containsKeys("zScore", "windowSize", "mean", "stddev");
        assertThat(Integer.parseInt(alert.getMetadata().get("windowSize"))).isEqualTo(29);
        // mean should be close to 0 (ε-noise)
        assertThat(Double.parseDouble(alert.getMetadata().get("mean"))).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.01));
        // stddev should be very small (ε-noise returns)
        assertThat(Double.parseDouble(alert.getMetadata().get("stddev"))).isLessThan(0.01);
    }

    // ── Orderbook depth tests ──────────────────────────────────────────────

    /** Spike series that always fires (30 ε-noise snapshots + spike to 0.54). */
    private static List<PriceSnapshot> spikeSnapshots() {
        double[] prices = new double[30];
        for (int i = 0; i < 29; i++) {
            prices[i] = 0.50 + (i % 2 == 0 ? 0.0001 : -0.0001);
        }
        prices[29] = 0.54;
        return series("m1", prices);
    }

    @Test
    void alertWithOrderbookDataIncludesSpreadAndDepth() {
        Market m = market("m1", "100000");
        m.setYesTokenId("tok-yes-1");
        AlertService spy = mock(AlertService.class);
        when(spy.tryCreate(any())).thenReturn(true);

        OrderbookService bookService = mock(OrderbookService.class);
        when(bookService.capture("tok-yes-1"))
                .thenReturn(Optional.of(new OrderbookService.BookSnapshot(198.02, 908.0)));

        TestableDetector det = detector(spy, spikeSnapshots(), bookService);
        det.checkMarket(m, NOW);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(spy).tryCreate(captor.capture());
        Alert alert = captor.getValue();

        assertThat(alert.getMetadata().get("spreadBps")).isEqualTo("198.02");
        assertThat(alert.getMetadata().get("depthAtMid")).isEqualTo("908.00");
    }

    @Test
    void alertFiredWhenClobCallFails() {
        Market m = market("m1", "100000");
        m.setYesTokenId("tok-yes-1");
        AlertService spy = mock(AlertService.class);
        when(spy.tryCreate(any())).thenReturn(true);

        OrderbookService bookService = mock(OrderbookService.class);
        when(bookService.capture(anyString())).thenThrow(new RuntimeException("CLOB 500"));

        TestableDetector det = detector(spy, spikeSnapshots(), bookService);
        det.checkMarket(m, NOW);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(spy).tryCreate(captor.capture());
        Alert alert = captor.getValue();

        assertThat(alert.getType()).isEqualTo("statistical_anomaly");
        assertThat(alert.getMetadata()).doesNotContainKey("spreadBps");
        assertThat(alert.getMetadata()).doesNotContainKey("depthAtMid");
    }

    @Test
    void alertFiredWhenClobCallTimesOut() {
        Market m = market("m1", "100000");
        m.setYesTokenId("tok-yes-1");
        AlertService spy = mock(AlertService.class);
        when(spy.tryCreate(any())).thenReturn(true);

        OrderbookService bookService = mock(OrderbookService.class);
        when(bookService.capture(anyString())).thenReturn(Optional.empty());

        TestableDetector det = detector(spy, spikeSnapshots(), bookService);
        det.checkMarket(m, NOW);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(spy).tryCreate(captor.capture());
        Alert alert = captor.getValue();

        assertThat(alert.getType()).isEqualTo("statistical_anomaly");
        assertThat(alert.getMetadata()).doesNotContainKey("spreadBps");
        assertThat(alert.getMetadata()).doesNotContainKey("depthAtMid");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void verifyNoAlert(Market market, List<PriceSnapshot> snapshots) {
        AlertService spy = mock(AlertService.class);
        TestableDetector det = detector(spy, snapshots);

        boolean result = det.checkMarket(market, NOW);

        assertThat(result).isFalse();
        verify(spy, never()).tryCreate(any());
    }

    private TestableDetector detector(AlertService service, List<PriceSnapshot> snapshots) {
        return detector(service, snapshots, null);
    }

    private TestableDetector detector(AlertService service, List<PriceSnapshot> snapshots,
                                       OrderbookService bookService) {
        return new TestableDetector(service, snapshots, bookService);
    }

    /**
     * Test-only subclass that overrides querySnapshots and captureOrderbook.
     */
    private static class TestableDetector extends StatisticalAnomalyDetector {
        private final List<PriceSnapshot> cannedSnapshots;
        private final OrderbookService bookService;

        TestableDetector(AlertService alertService, List<PriceSnapshot> snapshots,
                         OrderbookService bookService) {
            super(
                    null, // marketsTable — not used in unit tests
                    null, // snapshotsTable — not used in unit tests
                    alertService,
                    mock(OrderbookService.class),
                    fixedClock(),
                    Z_SCORE_THRESHOLD,
                    MIN_SNAPSHOTS,
                    MIN_VOLUME,
                    DEDUPE_WINDOW_MINUTES,
                    MIN_DELTA_P
            );
            this.cannedSnapshots = snapshots;
            this.bookService = bookService;
        }

        @Override
        List<PriceSnapshot> querySnapshots(String marketId, Instant now) {
            return cannedSnapshots;
        }

        @Override
        Optional<OrderbookService.BookSnapshot> captureOrderbook(String yesTokenId) {
            if (bookService == null) {
                return Optional.empty();
            }
            try {
                return bookService.capture(yesTokenId);
            } catch (Exception e) {
                return Optional.empty();
            }
        }

        private static AppClock fixedClock() {
            AppClock c = new AppClock();
            c.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
            return c;
        }
    }
}
