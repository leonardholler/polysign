package com.polysign.detector;

import com.polysign.alert.AlertService;
import com.polysign.common.AppClock;
import com.polysign.model.Alert;
import com.polysign.model.Market;
import com.polysign.model.PriceSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PriceMovementDetector}.
 *
 * <p>All tests exercise {@code checkMarket()} and {@code findMaxMove()} directly
 * with synthetic data — no DynamoDB, no scheduler.
 *
 * <p>Tier boundaries: Tier 1 &gt; $250k, Tier 2 ≥ $50k.
 * Thresholds: Tier 1 = 8%, Tier 2 = 14%, Tier 3 = 20%.
 * Resolution zone: high &gt; 0.65, low &lt; 0.35.
 * Mid-range multiplier: 2.0 (mid-range moves require 2× tier threshold).
 * Zone-entry discount: 0.25 (ENTERED_* moves get 25% lower threshold).
 */
class PriceMovementDetectorTest {

    private static final Instant NOW = Instant.parse("2026-04-09T12:00:00Z");

    // Tier thresholds
    static final double THRESHOLD_PCT_T1 = 8.0;
    static final double THRESHOLD_PCT_T2 = 14.0;
    static final double THRESHOLD_PCT_T3 = 20.0;

    // Tier volume boundaries
    static final double TIER1_MIN_VOLUME = 250_000.0;
    static final double TIER2_MIN_VOLUME =  50_000.0;

    // Orderbook gate defaults
    static final double MAX_SPREAD_BPS    = 500.0;
    static final double MIN_DEPTH_AT_MID  = 100.0;

    // Resolution zone config
    static final double RESOLUTION_ZONE_HIGH       = 0.65;
    static final double RESOLUTION_ZONE_LOW        = 0.35;
    static final double MID_RANGE_MULTIPLIER       = 2.0;
    static final double ZONE_ENTRY_DISCOUNT        = 0.25;
    static final double MIN_WINDOW_VOLUME          = 5000.0;
    static final double HIGH_VOLUME_WINDOW_THRESHOLD = 20000.0;

    static final int    WINDOW_MINUTES        = 15;
    static final int    DEDUPE_WINDOW_MINUTES = 30;
    static final double MIN_DELTA_P           = 0.03;

    private AlertService alertService;
    private PriceMovementDetector detector;

    @BeforeEach
    void setUp() {
        alertService = mock(AlertService.class);
        when(alertService.tryCreate(any())).thenReturn(true);

        AppClock clock = new AppClock();
        clock.setClock(Clock.fixed(NOW, ZoneOffset.UTC));

        detector = new PriceMovementDetector(
                null, null, null, alertService,
                mock(OrderbookService.class), clock,
                new SimpleMeterRegistry(),
                THRESHOLD_PCT_T1, THRESHOLD_PCT_T2, THRESHOLD_PCT_T3,
                WINDOW_MINUTES,
                TIER1_MIN_VOLUME, TIER2_MIN_VOLUME,
                MAX_SPREAD_BPS, MIN_DEPTH_AT_MID,
                RESOLUTION_ZONE_HIGH, RESOLUTION_ZONE_LOW,
                MID_RANGE_MULTIPLIER, ZONE_ENTRY_DISCOUNT,
                MIN_WINDOW_VOLUME, HIGH_VOLUME_WINDOW_THRESHOLD,
                DEDUPE_WINDOW_MINUTES, MIN_DELTA_P, 3,
                new MarketLivenessGate(clock),
                new com.polysign.config.CommonDetectorProperties()
        );
    }

    // ── Helper builders ──────────────────────────────────────────────────────

    private static Market market(String id, String volume24h, boolean watched) {
        Market m = new Market();
        m.setMarketId(id);
        m.setQuestion("Will X happen?");
        m.setVolume24h(volume24h);
        m.setIsWatched(watched);
        return m;
    }

    private static PriceSnapshot snap(String marketId, Instant time, String midpoint) {
        PriceSnapshot s = new PriceSnapshot();
        s.setMarketId(marketId);
        s.setTimestamp(time.toString());
        s.setMidpoint(new BigDecimal(midpoint));
        s.setYesPrice(new BigDecimal(midpoint));
        s.setNoPrice(BigDecimal.ONE.subtract(new BigDecimal(midpoint)));
        return s;
    }

    // ── Flat series → no alert ───────────────────────────────────────────────

    @Test
    void flatSeriesProducesNoAlert() {
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(10)), "0.70"),
                snap("m1", NOW.minus(Duration.ofMinutes(5)),  "0.70"),
                snap("m1", NOW,                               "0.70")
        );

        PriceMovementDetector.MoveResult move = detector.findMaxMove(snapshots);
        assertThat(move).satisfiesAnyOf(
                m -> assertThat(m).isNull(),
                m -> assertThat(m.pctChange()).isCloseTo(0.0, within(0.01))
        );
    }

    // ── Slow drift → no alert ────────────────────────────────────────────────

    @Test
    void slowDriftOverLongPeriodProducesNoAlert() {
        // 10% drift over 30 min in resolution zone — below T1 threshold (8% raw but
        // the total move across a 30-min window exceeds the 15-min window, so maxMove
        // for any 15-min slice is smaller).
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(30)), "0.70"),
                snap("m1", NOW.minus(Duration.ofMinutes(20)), "0.7167"),
                snap("m1", NOW.minus(Duration.ofMinutes(10)), "0.7333"),
                snap("m1", NOW,                               "0.75")
        );

        PriceMovementDetector.MoveResult move = detector.findMaxMove(snapshots);
        assertThat(move).isNotNull();
        // Max move in any 15-min window is ~4.9% (0.7167→0.75) — below T1 8%
        assertThat(move.pctChange()).isLessThan(THRESHOLD_PCT_T1);
    }

    // ── Tier 1 bullish zone: 10% spike → alert ───────────────────────────────
    // 0.70→0.77: DEEPENING_BULLISH, T1 fallback discount applied → effective=6%.

    @Test
    void tier1Market10PctSpikeFiresAlert() {
        Market m = market("m1", "300000", false); // Tier 1
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(10)), "0.70"),
                snap("m1", NOW.minus(Duration.ofMinutes(5)),  "0.70"),
                snap("m1", NOW,                               "0.77") // 10% up, bullish zone
        );

        PriceMovementDetector.MoveResult move = detector.findMaxMove(snapshots);
        assertThat(move).isNotNull();
        assertThat(move.pctChange()).isCloseTo(10.0, within(0.1));
        assertThat(move.spanMinutes()).isEqualTo(5);

        verifyAlertCreated(m, snapshots, "warning", false, "TIER_1");
    }

    // ── Tier 1 bullish zone: 9% spike → alert ───────────────────────────────

    @Test
    void tier1Market9PctFiresAlert() {
        Market m = market("m1", "300000", false); // $300k = Tier 1
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.70"),
                snap("m1", NOW,                              "0.763") // ~9% up
        );

        verifyAlertCreated(m, snapshots, "warning", false, "TIER_1");
    }

    // ── Tier 1 resolution zone: 20% spike → bypasses dedupe ─────────────────
    // 0.70→0.84: DEEPENING_BULLISH. T1 effective≈6%. 20% ≥ 2×8%=16% → bypass.

    @Test
    void tier1Sudden20PctSpikeBypassesDedupe() {
        Market m = market("m1", "300000", false); // Tier 1
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.70"),
                snap("m1", NOW,                              "0.84") // 20% up
        );

        verifyAlertCreated(m, snapshots, "warning", true, "TIER_1");
    }

    // ── NEW: Mid-range move below 2x threshold → NO alert ───────────────────
    // REWRITE.md: "mid-range move (50¢→58¢, 16%) should NOT fire at Tier 1
    // (below 2x threshold of 16%)"
    // 0.50→0.57: 14% move in mid-range. T1 effective=2×8%=16%. 14%<16% → no alert.

    @Test
    void midRangeMoveBelowDoubleThresholdProducesNoAlert() {
        Market m = market("m1", "300000", false); // Tier 1
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.50"),
                snap("m1", NOW,                              "0.57") // 14% up, mid-range
        );

        verifyNoAlert(m, snapshots);
    }

    // ── NEW: Resolution zone move fires at lower threshold ───────────────────
    // REWRITE.md: "resolution zone move (80¢→88¢, 10%) SHOULD fire at Tier 1"
    // 0.80→0.88: DEEPENING_BULLISH T1. Effective=6%. 10%≥6% → fires.

    @Test
    void resolutionZoneMoveFiresAtLowerThreshold() {
        Market m = market("m1", "300000", false); // Tier 1
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.80"),
                snap("m1", NOW,                              "0.88") // 10% up, bullish zone
        );

        verifyAlertCreated(m, snapshots, "warning", false, "TIER_1");
    }

    // ── NEW: Zone entry move fires with ENTERED_BULLISH metadata ─────────────
    // REWRITE.md: "zone entry move (48¢→67¢) SHOULD fire with lowered threshold
    // and metadata zoneTransition=ENTERED_BULLISH"
    // 0.48→0.67: ENTERED_BULLISH, from mid-range into >65¢. T1 entry discount=25%→effective=6%.

    @Test
    void zoneEntryMoveFiresWithEnteredBullishMetadata() {
        Market m = market("m1", "300000", false); // Tier 1
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.48"),
                snap("m1", NOW,                              "0.67") // ~39.6% up — entered bullish zone
        );

        AlertService spy = mock(AlertService.class);
        when(spy.tryCreate(any())).thenReturn(true);

        TestableDetector det = new TestableDetector(spy, snapshots, null);
        boolean result = det.checkMarket(m, NOW);

        assertThat(result).isTrue();
        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(spy).tryCreate(captor.capture());
        Alert alert = captor.getValue();
        assertThat(alert.getMetadata().get("zoneTransition")).isEqualTo("ENTERED_BULLISH");
        assertThat(alert.getMetadata().get("direction")).isEqualTo("up");
    }

    // ── NEW: Dead momentum → no alert ────────────────────────────────────────
    // REWRITE.md: "move lacks momentum (big gap between old and new snapshot but
    // last 3 snapshots flat) should NOT fire"
    // Big historical move 0.70→0.77, then last 3 snapshots reverse/flatten.

    @Test
    void deadMomentumProducesNoAlert() {
        Market m = market("m1", "300000", false); // Tier 1
        // Big move happened 10 min ago, then price reversed — momentum is dead
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(12)), "0.70"),
                snap("m1", NOW.minus(Duration.ofMinutes(10)), "0.77"), // big jump (10% up)
                snap("m1", NOW.minus(Duration.ofMinutes(4)),  "0.755"), // reversed
                snap("m1", NOW.minus(Duration.ofMinutes(2)),  "0.748"), // still declining
                snap("m1", NOW,                               "0.740")  // still declining
        );

        // hasMomentum: latest move is 0.748→0.740 (DOWN) while alert direction=up → no momentum
        verifyNoAlert(m, snapshots);
    }

    // ── NEW: Jittery momentum still fires ────────────────────────────────────
    // Intermediate snapshot has a slight jitter (wrong direction), but the latest
    // snapshot-to-snapshot move is correct. New momentum logic only checks the latest move.

    @Test
    void jitteryMomentumStillFires() {
        Market m = market("m1", "300000", false); // Tier 1
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(10)), "0.70"),
                snap("m1", NOW.minus(Duration.ofMinutes(5)),  "0.695"), // slight jitter DOWN
                snap("m1", NOW,                               "0.77")   // strong UP — alert direction
        );

        // Old logic: step1 (0.695 >= 0.70) = false → no alert (false negative)
        // New logic: only check latest (0.77 > 0.695) = true → alert fires
        verifyAlertCreated(m, snapshots, "warning", false, "TIER_1");
    }

    // ── NEW: Negligible window volume → no alert ──────────────────────────────
    // REWRITE.md: "large percentage move on negligible window volume (<$5k)
    // should NOT fire (if per-window volume is implemented)"
    // Snapshots with DIFFERENT volume24h values indicating actual trades are minimal.

    @Test
    void negligibleWindowVolumeProducesNoAlert() {
        Market m = market("m1", "300000", false); // Tier 1
        // volume24h changes by only $100 across the window — below $5k minWindowVolume
        PriceSnapshot s1 = snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.70");
        s1.setVolume24h(new BigDecimal("100000"));  // at window start

        PriceSnapshot s2 = snap("m1", NOW, "0.77"); // 10% up
        s2.setVolume24h(new BigDecimal("100100"));  // only $100 more — negligible

        List<PriceSnapshot> snapshots = List.of(s1, s2);

        verifyNoAlert(m, snapshots);
    }

    // ── Tier 2 market: below mid-range threshold → NO alert ──────────────────
    // 0.50→0.575 = 15%, but T2 mid-range effective = 2×14%=28%. 15%<28% → no alert.

    @Test
    void tier2MarketBelow14PctProducesNoAlert() {
        Market m = market("m1", "100000", false); // $100k = Tier 2
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.50"),
                snap("m1", NOW,                              "0.55") // 10% — well below 28%
        );

        verifyNoAlert(m, snapshots);
    }

    // ── Tier 2 bullish zone: 15% spike → alert ───────────────────────────────
    // 0.70→0.805: DEEPENING_BULLISH T2. T2 base=14%. No fallback (T2 vol < $250k).
    // Effective = 14%. 15%≥14% → fires.

    @Test
    void tier2Market15PctFiresAlert() {
        Market m = market("m1", "100000", false); // $100k = Tier 2
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.70"),
                snap("m1", NOW,                              "0.805") // ~15% up, bullish zone
        );

        // Orderbook returns empty (simulates API failure) → alert fires anyway per spec
        verifyAlertCreated(m, snapshots, "warning", false, "TIER_2");
    }

    // ── Tier 3 market: mid-range below threshold → NO alert ──────────────────
    // 0.50→0.575 = 15%. T3 mid-range effective = 2×20%=40%. 15%<40% → no alert.

    @Test
    void tier3MarketBelow20PctProducesNoAlert() {
        Market m = market("m1", "10000", false); // $10k = Tier 3
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.50"),
                snap("m1", NOW,                              "0.575")
        );

        verifyNoAlert(m, snapshots);
    }

    // ── Tier 3 bullish zone: 22% spike → alert ───────────────────────────────
    // 0.70→0.854: DEEPENING_BULLISH T3. Effective=20%. 22%≥20% → fires.

    @Test
    void tier3Market22PctFiresAlert() {
        Market m = market("m1", "10000", false); // $10k = Tier 3
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.70"),
                snap("m1", NOW,                              "0.854") // ~22% up, bullish zone
        );

        // Orderbook returns empty (call failure) → alert fires anyway per spec
        // 22% < 2×20%=40%, so dedupe is NOT bypassed
        verifyAlertCreated(m, snapshots, "warning", false, "TIER_3");
    }

    // ── Orderbook depth gate: Tier 2 + spread too wide → suppressed ──────────

    @Test
    void tier2AlertSuppressedWhenSpreadTooWide() {
        Market m = market("m1", "100000", false); // Tier 2
        m.setYesTokenId("tok-1");
        // 15% spike in bullish zone — above Tier 2 threshold (14%)
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.70"),
                snap("m1", NOW,                              "0.805")
        );

        AlertService spy = mock(AlertService.class);
        OrderbookService bookService = mock(OrderbookService.class);
        // spread=800 > maxSpreadBps=500 → gate should suppress
        when(bookService.capture("tok-1"))
                .thenReturn(Optional.of(new OrderbookService.BookSnapshot(800.0, 500.0)));

        TestableDetector det = new TestableDetector(spy, snapshots, bookService);
        boolean result = det.checkMarket(m, NOW);

        assertThat(result).isFalse();
        verify(spy, never()).tryCreate(any());
    }

    // ── Orderbook depth gate: Tier 2 + depth too low → suppressed ────────────

    @Test
    void tier2AlertSuppressedWhenDepthTooLow() {
        Market m = market("m1", "100000", false); // Tier 2
        m.setYesTokenId("tok-1");
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.70"),
                snap("m1", NOW,                              "0.805") // 15%
        );

        AlertService spy = mock(AlertService.class);
        OrderbookService bookService = mock(OrderbookService.class);
        // spread=300 OK, depth=50 < minDepthAtMid=100 → gate should suppress
        when(bookService.capture("tok-1"))
                .thenReturn(Optional.of(new OrderbookService.BookSnapshot(300.0, 50.0)));

        TestableDetector det = new TestableDetector(spy, snapshots, bookService);
        boolean result = det.checkMarket(m, NOW);

        assertThat(result).isFalse();
        verify(spy, never()).tryCreate(any());
    }

    // ── Orderbook depth gate: Tier 2 + good book → fires ────────────────────

    @Test
    void tier2AlertFiresWhenBookPassesGate() {
        Market m = market("m1", "100000", false); // Tier 2
        m.setYesTokenId("tok-1");
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.70"),
                snap("m1", NOW,                              "0.805") // 15%
        );

        AlertService spy = mock(AlertService.class);
        when(spy.tryCreate(any())).thenReturn(true);
        OrderbookService bookService = mock(OrderbookService.class);
        // spread=300 < 500, depth=500 > 100 → gate passes
        when(bookService.capture("tok-1"))
                .thenReturn(Optional.of(new OrderbookService.BookSnapshot(300.0, 500.0)));

        TestableDetector det = new TestableDetector(spy, snapshots, bookService);
        boolean result = det.checkMarket(m, NOW);

        assertThat(result).isTrue();
        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(spy).tryCreate(captor.capture());
        Alert alert = captor.getValue();
        assertThat(alert.getType()).isEqualTo("price_movement");
        assertThat(alert.getMetadata().get("liquidityTier")).isEqualTo("TIER_2");
        assertThat(alert.getMetadata().get("spreadBps")).isEqualTo("300.00");
        assertThat(alert.getMetadata().get("depthAtMid")).isEqualTo("500.00");
    }

    // ── Orderbook failure on Tier 2 → alert fires anyway ─────────────────────

    @Test
    void tier2AlertFiresWhenOrderbookServiceFails() {
        Market m = market("m1", "100000", false); // Tier 2
        m.setYesTokenId("tok-1");
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.70"),
                snap("m1", NOW,                              "0.805") // 15%
        );

        AlertService spy = mock(AlertService.class);
        when(spy.tryCreate(any())).thenReturn(true);
        OrderbookService bookService = mock(OrderbookService.class);
        when(bookService.capture(anyString())).thenThrow(new RuntimeException("CLOB timeout"));

        TestableDetector det = new TestableDetector(spy, snapshots, bookService);
        boolean result = det.checkMarket(m, NOW);

        assertThat(result).isTrue();
        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(spy).tryCreate(captor.capture());
        Alert alert = captor.getValue();
        // Alert fires — book fields absent because call failed
        assertThat(alert.getMetadata()).doesNotContainKey("spreadBps");
        assertThat(alert.getMetadata()).doesNotContainKey("depthAtMid");
    }

    // ── Tier 1: gate does NOT apply even with bad spread ─────────────────────

    @Test
    void tier1AlertIgnoresOrderbookGate() {
        Market m = market("m1", "300000", false); // Tier 1 — no gate
        m.setYesTokenId("tok-1");
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.70"),
                snap("m1", NOW,                              "0.77") // 10%
        );

        AlertService spy = mock(AlertService.class);
        when(spy.tryCreate(any())).thenReturn(true);
        OrderbookService bookService = mock(OrderbookService.class);
        // Even a terrible spread — should not suppress a Tier 1 alert
        when(bookService.capture("tok-1"))
                .thenReturn(Optional.of(new OrderbookService.BookSnapshot(9999.0, 1.0)));

        TestableDetector det = new TestableDetector(spy, snapshots, bookService);
        boolean result = det.checkMarket(m, NOW);

        assertThat(result).isTrue();
        verify(spy).tryCreate(any());
    }

    // ── Watched vs unwatched severity ────────────────────────────────────────

    @Test
    void watchedMarketGetsCriticalSeverity() {
        Market m = market("m1", "300000", true); // Tier 1, watched
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.70"),
                snap("m1", NOW,                              "0.77") // 10%
        );

        verifyAlertCreated(m, snapshots, "critical", false, "TIER_1");
    }

    @Test
    void unwatchedMarketGetsWarningSeverity() {
        Market m = market("m1", "300000", false); // Tier 1, not watched
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.70"),
                snap("m1", NOW,                              "0.77") // 10%
        );

        verifyAlertCreated(m, snapshots, "warning", false, "TIER_1");
    }

    // ── Edge: single snapshot → no alert ─────────────────────────────────────

    @Test
    void singleSnapshotProducesNoAlert() {
        Market m = market("m1", "300000", false); // Tier 1
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW, "0.70")
        );

        verifyNoAlert(m, snapshots);
    }

    // ── Edge: price drop (negative direction) in bearish zone ────────────────
    // 0.28→0.247: ~12% down. DEEPENING_BEARISH T1 (both < 0.35). Effective=6%. Fires.

    @Test
    void priceDropAlsoFiresAlert() {
        Market m = market("m1", "300000", false); // Tier 1
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.28"),
                snap("m1", NOW,                              "0.246") // ~12% down, bearish zone
        );

        PriceMovementDetector.MoveResult move = detector.findMaxMove(snapshots);
        assertThat(move).isNotNull();
        assertThat(move.pctChange()).isGreaterThan(THRESHOLD_PCT_T1);

        verifyAlertCreated(m, snapshots, "warning", false, "TIER_1");
    }

    // ── Tail zone + tiny delta → no alert ───────────────────────────────────

    @Test
    void tailZoneTinyDeltaProducesNoAlert() {
        // 0.0045 → 0.0055: 22% pct move but delta=0.001, both prices < 0.05
        Market m = market("m1", "300000", false);
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.0045"),
                snap("m1", NOW,                              "0.0055")
        );

        verifyNoAlert(m, snapshots);
    }

    @Test
    void upperTailBothAbove95ProducesNoAlert() {
        // 0.96 → 0.99: both prices > 0.95 (upper tail zone)
        Market m = market("m1", "300000", false);
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.96"),
                snap("m1", NOW,                              "0.99")
        );

        verifyNoAlert(m, snapshots);
    }

    @Test
    void meaningfulMidRangeMoveFiresAlert() {
        // 0.45 → 0.55: 22% pct in mid-range. T1 mid-range effective=16%. 22%≥16% → fires.
        // bypassDedupe: 22% ≥ 2×8%=16% AND isActivelyMoving=true (T-5min snapshot)
        Market m = market("m1", "300000", false); // Tier 1
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.45"),
                snap("m1", NOW,                              "0.55")
        );

        verifyAlertCreated(m, snapshots, "warning", true, "TIER_1");
    }

    @Test
    void justAboveDeltaFloorFiresAlert() {
        // 0.145 → 0.18: 24% pct, delta=0.035 (just above the 0.03 floor)
        // MID_RANGE (both < 0.35 and direction=up, not bearish). T1 effective=16%. 24%≥16%.
        // bypassDedupe: 24% ≥ 16% AND isActivelyMoving=true
        Market m = market("m1", "300000", false); // Tier 1
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.145"),
                snap("m1", NOW,                              "0.18")
        );

        verifyAlertCreated(m, snapshots, "warning", true, "TIER_1");
    }

    // ── Stale-move filter: settled market does NOT fire ───────────────────────
    // Market moved 10% in bullish zone 10 min ago, then REVERSED. Dead momentum
    // (last 3 snapshots declining when alert direction=up) → no alert at all.

    @Test
    void staleMoveShouldNotBypassDedupe() {
        // Market moved from 0.70→0.77 (10% up, DEEPENING_BULLISH) 10 min ago,
        // then reversed. Last 3 snapshots are all DOWN while alert direction=up.
        // hasMomentum() returns false → no alert.
        Market m = market("m1", "300000", false); // Tier 1
        Instant t14 = NOW.minus(Duration.ofMinutes(14));
        Instant t5  = NOW.minus(Duration.ofMinutes(5));
        Instant t4  = NOW.minus(Duration.ofMinutes(4));
        Instant t3  = NOW.minus(Duration.ofMinutes(3));
        Instant t2  = NOW.minus(Duration.ofMinutes(2));
        Instant t1  = NOW.minus(Duration.ofMinutes(1));

        List<PriceSnapshot> snapshots = List.of(
                snap("m1", t14, "0.70"),   // before the move
                snap("m1", t5,  "0.77"),   // big jump (10% up)
                snap("m1", t4,  "0.765"),  // starting to reverse
                snap("m1", t3,  "0.758"),  // still declining
                snap("m1", t2,  "0.750"),  // still declining
                snap("m1", t1,  "0.742"),  // still declining
                snap("m1", NOW, "0.734")   // still declining — momentum is dead
        );

        // Alert does NOT fire: momentum check fails (last 3 are DOWN, alert=up)
        verifyNoAlert(m, snapshots);
    }

    // ── liquidityTier and zoneTransition in alert metadata ───────────────────

    @Test
    void alertMetadataContainsLiquidityTierAndZoneTransition() {
        Market m = market("m1", "100000", false); // $100k = Tier 2
        m.setYesTokenId("tok-1");
        // 15% move in bullish zone — above Tier 2 threshold
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.70"),
                snap("m1", NOW,                              "0.805")
        );

        AlertService spy = mock(AlertService.class);
        when(spy.tryCreate(any())).thenReturn(true);
        OrderbookService bookService = mock(OrderbookService.class);
        when(bookService.capture(anyString())).thenReturn(Optional.empty()); // simulate failure → fires

        TestableDetector det = new TestableDetector(spy, snapshots, bookService);
        det.checkMarket(m, NOW);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(spy).tryCreate(captor.capture());
        assertThat(captor.getValue().getMetadata().get("liquidityTier")).isEqualTo("TIER_2");
        assertThat(captor.getValue().getMetadata().get("zoneTransition")).isEqualTo("DEEPENING_BULLISH");
    }

    // ── Orderbook depth tests (bullish zone) ─────────────────────────────────

    private static final List<PriceSnapshot> TIER1_BULLISH_SNAPSHOTS = List.of(
            snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.70"),
            snap("m1", NOW,                              "0.77") // 10%, Tier 1, bullish
    );

    @Test
    void alertWithOrderbookDataIncludesSpreadAndDepth() {
        Market m = market("m1", "300000", false); // Tier 1 — book captured but gate not applied
        m.setYesTokenId("tok-yes-1");
        AlertService spy = mock(AlertService.class);
        when(spy.tryCreate(any())).thenReturn(true);

        OrderbookService bookService = mock(OrderbookService.class);
        when(bookService.capture("tok-yes-1"))
                .thenReturn(Optional.of(new OrderbookService.BookSnapshot(198.02, 908.0)));

        TestableDetector det = new TestableDetector(spy, TIER1_BULLISH_SNAPSHOTS, bookService);
        det.checkMarket(m, NOW);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(spy).tryCreate(captor.capture());
        Alert alert = captor.getValue();

        assertThat(alert.getMetadata().get("spreadBps")).isEqualTo("198.02");
        assertThat(alert.getMetadata().get("depthAtMid")).isEqualTo("908.00");
    }

    @Test
    void alertFiredWhenClobCallFails() {
        Market m = market("m1", "300000", false); // Tier 1 — gate not applied
        m.setYesTokenId("tok-yes-1");
        AlertService spy = mock(AlertService.class);
        when(spy.tryCreate(any())).thenReturn(true);

        OrderbookService bookService = mock(OrderbookService.class);
        when(bookService.capture(anyString())).thenThrow(new RuntimeException("CLOB 500"));

        TestableDetector det = new TestableDetector(spy, TIER1_BULLISH_SNAPSHOTS, bookService);
        det.checkMarket(m, NOW);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(spy).tryCreate(captor.capture());
        Alert alert = captor.getValue();

        assertThat(alert.getType()).isEqualTo("price_movement");
        assertThat(alert.getMetadata()).doesNotContainKey("spreadBps");
        assertThat(alert.getMetadata()).doesNotContainKey("depthAtMid");
    }

    @Test
    void alertFiredWhenClobCallTimesOut() {
        Market m = market("m1", "300000", false); // Tier 1
        m.setYesTokenId("tok-yes-1");
        AlertService spy = mock(AlertService.class);
        when(spy.tryCreate(any())).thenReturn(true);

        OrderbookService bookService = mock(OrderbookService.class);
        when(bookService.capture(anyString())).thenReturn(Optional.empty());

        TestableDetector det = new TestableDetector(spy, TIER1_BULLISH_SNAPSHOTS, bookService);
        det.checkMarket(m, NOW);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(spy).tryCreate(captor.capture());
        Alert alert = captor.getValue();

        assertThat(alert.getType()).isEqualTo("price_movement");
        assertThat(alert.getMetadata()).doesNotContainKey("spreadBps");
        assertThat(alert.getMetadata()).doesNotContainKey("depthAtMid");
    }

    // ── Helpers for checkMarket with injectable snapshots ─────────────────────

    private void verifyAlertCreated(Market market, List<PriceSnapshot> snapshots,
                                     String expectedSeverity, boolean expectBypassDedupe,
                                     String expectedTier) {
        AlertService spyService = mock(AlertService.class);
        when(spyService.tryCreate(any())).thenReturn(true);

        PriceMovementDetector testDetector = new TestableDetector(
                spyService, snapshots, null);

        testDetector.checkMarket(market, NOW);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(spyService).tryCreate(captor.capture());
        Alert alert = captor.getValue();

        assertThat(alert.getType()).isEqualTo("price_movement");
        assertThat(alert.getSeverity()).isEqualTo(expectedSeverity);
        assertThat(alert.getMarketId()).isEqualTo(market.getMarketId());
        assertThat(alert.getMetadata().get("bypassedDedupe"))
                .isEqualTo(String.valueOf(expectBypassDedupe));
        assertThat(alert.getMetadata().get("liquidityTier")).isEqualTo(expectedTier);
    }

    private void verifyNoAlert(Market market, List<PriceSnapshot> snapshots) {
        AlertService spyService = mock(AlertService.class);

        PriceMovementDetector testDetector = new TestableDetector(
                spyService, snapshots, null);

        boolean result = testDetector.checkMarket(market, NOW);

        assertThat(result).isFalse();
        verify(spyService, never()).tryCreate(any());
    }

    /**
     * Test-only subclass that overrides querySnapshots and captureOrderbook.
     */
    static class TestableDetector extends PriceMovementDetector {
        private final List<PriceSnapshot> cannedSnapshots;
        private final OrderbookService bookService;

        TestableDetector(AlertService alertService, List<PriceSnapshot> snapshots,
                         OrderbookService bookService) {
            super(null, null, null, alertService, mock(OrderbookService.class),
                  fixedClock(), new SimpleMeterRegistry(),
                  THRESHOLD_PCT_T1, THRESHOLD_PCT_T2, THRESHOLD_PCT_T3,
                  WINDOW_MINUTES,
                  TIER1_MIN_VOLUME, TIER2_MIN_VOLUME,
                  MAX_SPREAD_BPS, MIN_DEPTH_AT_MID,
                  RESOLUTION_ZONE_HIGH, RESOLUTION_ZONE_LOW,
                  MID_RANGE_MULTIPLIER, ZONE_ENTRY_DISCOUNT,
                  MIN_WINDOW_VOLUME, HIGH_VOLUME_WINDOW_THRESHOLD,
                  DEDUPE_WINDOW_MINUTES, MIN_DELTA_P, 3,
                  new MarketLivenessGate(fixedClock()),
                  new com.polysign.config.CommonDetectorProperties());
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

    // ── Fix 2 regression: detectedAt is raw clock, createdAt is dedupe bucket ─

    @Test
    void detectedAtIsRawClockAndCreatedAtIsBucket() {
        Instant midBucket = Instant.parse("2026-04-09T12:15:00Z");

        Market m = market("m1", "300000", false); // Tier 1
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", midBucket.minus(Duration.ofMinutes(5)), "0.70"),
                snap("m1", midBucket,                              "0.77") // 10% up, bullish
        );

        AlertService spy = mock(AlertService.class);
        when(spy.tryCreate(any())).thenReturn(true);

        AppClock fixedClock = new AppClock();
        fixedClock.setClock(Clock.fixed(midBucket, ZoneOffset.UTC));

        PriceMovementDetector det = new PriceMovementDetector(
                null, null, null, spy,
                mock(OrderbookService.class), fixedClock,
                new SimpleMeterRegistry(),
                THRESHOLD_PCT_T1, THRESHOLD_PCT_T2, THRESHOLD_PCT_T3,
                WINDOW_MINUTES,
                TIER1_MIN_VOLUME, TIER2_MIN_VOLUME,
                MAX_SPREAD_BPS, MIN_DEPTH_AT_MID,
                RESOLUTION_ZONE_HIGH, RESOLUTION_ZONE_LOW,
                MID_RANGE_MULTIPLIER, ZONE_ENTRY_DISCOUNT,
                MIN_WINDOW_VOLUME, HIGH_VOLUME_WINDOW_THRESHOLD,
                DEDUPE_WINDOW_MINUTES, MIN_DELTA_P, 3,
                new MarketLivenessGate(fixedClock),
                new com.polysign.config.CommonDetectorProperties()) {
            @Override
            List<PriceSnapshot> querySnapshots(String marketId, Instant now) { return snapshots; }
        };
        det.checkMarket(m, midBucket);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(spy).tryCreate(captor.capture());
        Alert alert = captor.getValue();

        assertThat(alert.getCreatedAt()).isEqualTo("2026-04-09T12:00:00Z");
        assertThat(alert.getMetadata().get("detectedAt"))
                .isEqualTo("2026-04-09T12:15:00Z");
        assertThat(alert.getMetadata().get("detectedAt"))
                .isNotEqualTo(alert.getCreatedAt());
    }

    // ── priceAtAlert is populated at fire time ────────────────────────────────

    @Test
    void priceAtAlert_isSetToMoveToPrice() {
        // 0.50 → 0.60: 20% move in resolution zone (Tier 1, threshold 8%) → fires
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(12)), "0.50"),
                snap("m1", NOW.minus(Duration.ofMinutes(6)),  "0.55"),
                snap("m1", NOW,                               "0.60")
        );
        Market m = market("m1", "300000", false);

        AlertService spy = mock(AlertService.class);
        when(spy.tryCreate(any())).thenReturn(true);

        // TestableDetector overrides querySnapshots so snapshotsTable (null) is never hit.
        TestableDetector det = new TestableDetector(spy, snapshots, null);
        det.checkMarket(m, NOW);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(spy).tryCreate(captor.capture());
        Alert alert = captor.getValue();

        assertThat(alert.getPriceAtAlert())
                .isNotNull()
                .isEqualByComparingTo(new BigDecimal("0.60")); // toPrice of the detected move
    }
}
