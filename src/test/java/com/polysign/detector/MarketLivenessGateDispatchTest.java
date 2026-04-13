package com.polysign.detector;

import com.polysign.alert.AlertService;
import com.polysign.common.AppClock;
import com.polysign.config.CommonDetectorProperties;
import com.polysign.model.Market;
import com.polysign.model.PriceSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

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
import static org.mockito.Mockito.*;

/**
 * Dispatch-level gate tests.
 *
 * <p>Proves that when {@link MarketLivenessGate} returns {@code false} for a market,
 * zero calls to {@link AlertService#tryCreate} occur for either the price-movement
 * or statistical-anomaly detector, regardless of how strong the signal is.
 *
 * <p>Each test seeds a series that <em>would</em> fire an alert on a live market,
 * then sets the market's {@code endDate} to the past and verifies no alert fires.
 */
class MarketLivenessGateDispatchTest {

    private static final Instant NOW = Instant.parse("2026-04-13T12:00:00Z");

    // ─────────────────────────────────────────────────────────────────────────────
    // PriceMovementDetector dispatch test
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * A Tier-1 market (volume > $250k) with a >8% move in 15 min would normally fire.
     * After setting endDate to the past, checkMarket must return false and never call
     * alertService.tryCreate.
     */
    @Test
    void priceMovementDetectorSkipsEndedMarket() {
        AlertService alertService = mock(AlertService.class);
        AppClock clock = fixedClock();

        // Market with endDate 2 hours before NOW
        Market market = liveMarket();
        market.setEndDate("2026-04-13T10:00:00Z"); // past → gate blocks

        // Snapshots: 0.50 → 0.60 in 10 min (20% move on T1 → would fire at 8% threshold)
        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(10)), "0.50"),
                snap("m1", NOW.minus(Duration.ofMinutes(5)),  "0.55"),
                snap("m1", NOW,                               "0.60")
        );

        PriceMovementDetector detector = new TestablePriceDetector(alertService, snapshots, clock);
        boolean result = detector.checkMarket(market, NOW);

        assertThat(result).isFalse();
        verify(alertService, never()).tryCreate(any());
    }

    /**
     * active=false on a Tier-1 market with a strong move → gate blocks, no alert.
     */
    @Test
    void priceMovementDetectorSkipsPausedMarket() {
        AlertService alertService = mock(AlertService.class);
        AppClock clock = fixedClock();

        Market market = liveMarket();
        market.setEndDate("2026-04-20T09:00:00Z"); // future
        market.setActive(false);                    // explicitly paused → gate blocks

        List<PriceSnapshot> snapshots = List.of(
                snap("m1", NOW.minus(Duration.ofMinutes(10)), "0.50"),
                snap("m1", NOW.minus(Duration.ofMinutes(5)),  "0.55"),
                snap("m1", NOW,                               "0.60")
        );

        PriceMovementDetector detector = new TestablePriceDetector(alertService, snapshots, clock);
        boolean result = detector.checkMarket(market, NOW);

        assertThat(result).isFalse();
        verify(alertService, never()).tryCreate(any());
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // StatisticalAnomalyDetector dispatch test
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * A sudden 5σ spike on a Tier-1 market would normally fire. After setting
     * endDate to the past, checkMarket must return false with no alert fired.
     */
    @Test
    void statisticalAnomalyDetectorSkipsEndedMarket() {
        AlertService alertService = mock(AlertService.class);
        AppClock clock = fixedClock();

        Market market = liveMarket();
        market.setEndDate("2026-04-13T10:00:00Z"); // past → gate blocks

        // ε-noise series with large spike (z >> 3.0) that would trigger Tier-1 threshold
        double[] prices = new double[30];
        for (int i = 0; i < 29; i++) {
            prices[i] = 0.50 + (i % 2 == 0 ? 0.0001 : -0.0001);
        }
        prices[29] = 0.56; // z >> 5σ on this base noise

        StatisticalAnomalyDetector detector = new TestableStatDetector(alertService, series(prices), clock);
        boolean result = detector.checkMarket(market, NOW);

        assertThat(result).isFalse();
        verify(alertService, never()).tryCreate(any());
    }

    /**
     * acceptingOrders=false → gate blocks even with a strong anomaly.
     */
    @Test
    void statisticalAnomalyDetectorSkipsFrozenOrderBookMarket() {
        AlertService alertService = mock(AlertService.class);
        AppClock clock = fixedClock();

        Market market = liveMarket();
        market.setEndDate("2026-04-20T09:00:00Z"); // future
        market.setAcceptingOrders(false);            // frozen → gate blocks

        double[] prices = new double[30];
        for (int i = 0; i < 29; i++) {
            prices[i] = 0.50 + (i % 2 == 0 ? 0.0001 : -0.0001);
        }
        prices[29] = 0.56;

        StatisticalAnomalyDetector detector = new TestableStatDetector(alertService, series(prices), clock);
        boolean result = detector.checkMarket(market, NOW);

        assertThat(result).isFalse();
        verify(alertService, never()).tryCreate(any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private static AppClock fixedClock() {
        AppClock c = new AppClock();
        c.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        return c;
    }

    private static Market liveMarket() {
        Market m = new Market();
        m.setMarketId("m1");
        m.setQuestion("Will X happen?");
        m.setVolume24h("300000"); // Tier 1
        m.setIsWatched(false);
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

    private static List<PriceSnapshot> series(double... prices) {
        List<PriceSnapshot> snaps = new ArrayList<>();
        int n = prices.length;
        for (int i = 0; i < n; i++) {
            Instant t = NOW.minus(Duration.ofMinutes(n - 1 - i));
            PriceSnapshot s = new PriceSnapshot();
            s.setMarketId("m1");
            s.setTimestamp(t.toString());
            s.setMidpoint(BigDecimal.valueOf(prices[i]));
            s.setYesPrice(BigDecimal.valueOf(prices[i]));
            s.setNoPrice(BigDecimal.ONE.subtract(BigDecimal.valueOf(prices[i])));
            snaps.add(s);
        }
        return snaps;
    }

    // ── Testable subclasses (override DynamoDB queries with canned data) ──────────

    private static class TestablePriceDetector extends PriceMovementDetector {
        private final List<PriceSnapshot> canned;

        TestablePriceDetector(AlertService alertService, List<PriceSnapshot> snapshots,
                               AppClock clock) {
            super(
                    null, null, null, alertService,
                    mock(OrderbookService.class), clock,
                    new SimpleMeterRegistry(),
                    8.0, 14.0, 20.0,  // tier thresholds
                    15,               // windowMinutes
                    250_000.0, 50_000.0,  // tier boundaries
                    500.0, 100.0,     // orderbook gate
                    0.65, 0.35,       // resolution zone
                    2.0, 0.25,        // midRange multiplier, zone-entry discount
                    5000.0, 20000.0,  // minWindowVolume, highVolumeWindowThreshold
                    30, 0.02, 3,      // dedupeWindowMinutes, minDeltaP, maxBypassPerHour
                    new MarketLivenessGate(clock),
                    new CommonDetectorProperties()
            );
            this.canned = snapshots;
        }

        @Override
        List<PriceSnapshot> querySnapshots(String marketId, Instant now) {
            return canned;
        }

        @Override
        Optional<OrderbookService.BookSnapshot> captureOrderbook(String yesTokenId) {
            return Optional.empty();
        }
    }

    private static class TestableStatDetector extends StatisticalAnomalyDetector {
        private final List<PriceSnapshot> canned;

        TestableStatDetector(AlertService alertService, List<PriceSnapshot> snapshots,
                              AppClock clock) {
            super(
                    null, null, alertService,
                    mock(OrderbookService.class), clock,
                    new SimpleMeterRegistry(),
                    3.0, 4.0, 5.0,   // z-score thresholds
                    20,              // minSnapshots
                    250_000.0, 50_000.0,  // tier boundaries
                    500.0, 100.0,    // orderbook gate
                    30, 0.03,        // dedupeWindowMinutes, minDeltaP
                    new MarketLivenessGate(clock),
                    new CommonDetectorProperties()
            );
            this.canned = snapshots;
        }

        @Override
        List<PriceSnapshot> querySnapshots(String marketId, Instant now) {
            return canned;
        }

        @Override
        Optional<OrderbookService.BookSnapshot> captureOrderbook(String yesTokenId) {
            return Optional.empty();
        }
    }
}
