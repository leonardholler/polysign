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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PriceMovementDetector}.
 *
 * <p>All tests exercise {@code checkMarket()} and {@code findMaxMove()} directly
 * with synthetic data — no DynamoDB, no scheduler.
 */
class PriceMovementDetectorTest {

    private static final Instant NOW = Instant.parse("2026-04-09T12:00:00Z");
    private static final double THRESHOLD_PCT = 8.0;
    private static final int WINDOW_MINUTES = 15;
    private static final double MIN_VOLUME = 50_000.0;
    private static final int DEDUPE_WINDOW_MINUTES = 30;

    private AlertService alertService;
    private PriceMovementDetector detector;

    @BeforeEach
    void setUp() {
        alertService = mock(AlertService.class);
        when(alertService.tryCreate(any())).thenReturn(true);

        AppClock clock = new AppClock();
        clock.setClock(Clock.fixed(NOW, ZoneOffset.UTC));

        // Construct detector directly — DynamoDB tables are null because
        // we only test checkMarket() and findMaxMove(), never detect().
        detector = new PriceMovementDetector(
                null, // marketsTable — not used in unit tests
                null, // snapshotsTable — not used in unit tests
                alertService,
                clock,
                THRESHOLD_PCT,
                WINDOW_MINUTES,
                MIN_VOLUME,
                DEDUPE_WINDOW_MINUTES
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
                snap("m1", NOW.minus(Duration.ofMinutes(10)), "0.50"),
                snap("m1", NOW.minus(Duration.ofMinutes(5)),  "0.50"),
                snap("m1", NOW,                               "0.50")
        );

        PriceMovementDetector.MoveResult move = detector.findMaxMove(snapshots);
        // 0% change — below threshold
        assertThat(move).satisfiesAnyOf(
                m -> assertThat(m).isNull(),
                m -> assertThat(m.pctChange()).isCloseTo(0.0, within(0.01))
        );
    }

    // ── Slow drift → no alert ────────────────────────────────────────────────

    @Test
    void slowDriftOverLongPeriodProducesNoAlert() {
        // 10% move spread over 30 minutes (>15-min window) — no pair within
        // 15 minutes has ≥8% change because each step is only ~3.3%
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(30)), "0.50"),
                snap("m1", NOW.minus(Duration.ofMinutes(20)), "0.5167"),
                snap("m1", NOW.minus(Duration.ofMinutes(10)), "0.5333"),
                snap("m1", NOW,                               "0.55")
        );

        PriceMovementDetector.MoveResult move = detector.findMaxMove(snapshots);
        // Max move within any 15-min window: 0.50→0.5167 = 3.3%, or 0.5167→0.5333 = 3.2%, etc.
        assertThat(move).isNotNull();
        assertThat(move.pctChange()).isLessThan(THRESHOLD_PCT);
    }

    // ── Sudden 10% spike → alert ─────────────────────────────────────────────

    @Test
    void sudden10PctSpikeFiresAlert() {
        Market m = market("m1", "100000", false);
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(10)), "0.50"),
                snap("m1", NOW.minus(Duration.ofMinutes(5)),  "0.50"),
                snap("m1", NOW,                               "0.55") // 10% up
        );

        // Verify the move is detected
        PriceMovementDetector.MoveResult move = detector.findMaxMove(snapshots);
        assertThat(move).isNotNull();
        assertThat(move.pctChange()).isCloseTo(10.0, within(0.01));
        assertThat(move.spanMinutes()).isEqualTo(5);

        // Verify alert is created via checkMarket
        // We need to make checkMarket work without DynamoDB — so we test findMaxMove
        // directly above, then verify the alertService call below with a full
        // integration-style test using a subclass that overrides querySnapshots.
        verifyAlertCreated(m, snapshots, "warning", false);
    }

    // ── Sudden 20% spike → alert (bypasses dedupe) ──────────────────────────

    @Test
    void sudden20PctSpikeBypassesDedupe() {
        Market m = market("m1", "100000", false);
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.50"),
                snap("m1", NOW,                              "0.60") // 20% up
        );

        PriceMovementDetector.MoveResult move = detector.findMaxMove(snapshots);
        assertThat(move).isNotNull();
        assertThat(move.pctChange()).isCloseTo(20.0, within(0.01));

        verifyAlertCreated(m, snapshots, "warning", true);
    }

    // ── Low volume spike → no alert ──────────────────────────────────────────

    @Test
    void lowVolumeSpikeProducesNoAlert() {
        Market m = market("m1", "10000", false); // below $50k threshold
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.50"),
                snap("m1", NOW,                              "0.60") // 20% but low volume
        );

        verifyNoAlert(m, snapshots);
    }

    // ── Watched vs unwatched severity ────────────────────────────────────────

    @Test
    void watchedMarketGetsCriticalSeverity() {
        Market m = market("m1", "100000", true); // watched
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.50"),
                snap("m1", NOW,                              "0.55") // 10% up
        );

        verifyAlertCreated(m, snapshots, "critical", false);
    }

    @Test
    void unwatchedMarketGetsWarningSeverity() {
        Market m = market("m1", "100000", false); // not watched
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.50"),
                snap("m1", NOW,                              "0.55") // 10% up
        );

        verifyAlertCreated(m, snapshots, "warning", false);
    }

    // ── Edge: single snapshot → no alert ─────────────────────────────────────

    @Test
    void singleSnapshotProducesNoAlert() {
        Market m = market("m1", "100000", false);
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW, "0.50")
        );

        verifyNoAlert(m, snapshots);
    }

    // ── Edge: price drop (negative direction) ────────────────────────────────

    @Test
    void priceDropAlsoFiresAlert() {
        Market m = market("m1", "100000", false);
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(5)), "0.55"),
                snap("m1", NOW,                              "0.50") // ~9.1% down
        );

        PriceMovementDetector.MoveResult move = detector.findMaxMove(snapshots);
        assertThat(move).isNotNull();
        assertThat(move.pctChange()).isGreaterThan(THRESHOLD_PCT);

        verifyAlertCreated(m, snapshots, "warning", false);
    }

    // ── Helpers for checkMarket with injectable snapshots ─────────────────────

    /**
     * Creates a testable detector subclass that returns canned snapshots
     * instead of querying DynamoDB, then verifies the alert.
     */
    private void verifyAlertCreated(Market market, List<PriceSnapshot> snapshots,
                                     String expectedSeverity, boolean expectBypassDedupe) {
        AlertService spyService = mock(AlertService.class);
        when(spyService.tryCreate(any())).thenReturn(true);

        PriceMovementDetector testDetector = new TestableDetector(
                spyService, snapshots, THRESHOLD_PCT, WINDOW_MINUTES,
                MIN_VOLUME, DEDUPE_WINDOW_MINUTES);

        testDetector.checkMarket(market, NOW);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(spyService).tryCreate(captor.capture());
        Alert alert = captor.getValue();

        assertThat(alert.getType()).isEqualTo("price_movement");
        assertThat(alert.getSeverity()).isEqualTo(expectedSeverity);
        assertThat(alert.getMarketId()).isEqualTo(market.getMarketId());
        assertThat(alert.getMetadata().get("bypassedDedupe"))
                .isEqualTo(String.valueOf(expectBypassDedupe));
    }

    private void verifyNoAlert(Market market, List<PriceSnapshot> snapshots) {
        AlertService spyService = mock(AlertService.class);

        PriceMovementDetector testDetector = new TestableDetector(
                spyService, snapshots, THRESHOLD_PCT, WINDOW_MINUTES,
                MIN_VOLUME, DEDUPE_WINDOW_MINUTES);

        boolean result = testDetector.checkMarket(market, NOW);

        assertThat(result).isFalse();
        verify(spyService, never()).tryCreate(any());
    }

    /**
     * Test-only subclass that overrides querySnapshots to return canned data.
     */
    private static class TestableDetector extends PriceMovementDetector {
        private final List<PriceSnapshot> cannedSnapshots;

        TestableDetector(AlertService alertService, List<PriceSnapshot> snapshots,
                         double thresholdPct, int windowMinutes,
                         double minVolumeUsdc, int dedupeWindowMinutes) {
            super(null, null, alertService,
                  fixedClock(), thresholdPct, windowMinutes,
                  minVolumeUsdc, dedupeWindowMinutes);
            this.cannedSnapshots = snapshots;
        }

        @Override
        List<PriceSnapshot> querySnapshots(String marketId, Instant now) {
            return cannedSnapshots;
        }

        private static AppClock fixedClock() {
            AppClock c = new AppClock();
            c.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
            return c;
        }
    }
}
