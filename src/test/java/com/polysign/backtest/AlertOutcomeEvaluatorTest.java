package com.polysign.backtest;

import com.polysign.common.AppClock;
import com.polysign.model.Alert;
import com.polysign.model.AlertOutcome;
import com.polysign.model.PriceSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link AlertOutcomeEvaluator} — strict TDD.
 *
 * Tests were written BEFORE the implementation and run red first.
 * Uses a testable subclass that stubs all DynamoDB calls.
 */
class AlertOutcomeEvaluatorTest {

    // NOW is 2 hours after T so that t15m and t1h horizons are both due.
    private static final Instant T   = Instant.parse("2026-04-09T10:00:00Z");
    private static final Instant NOW = T.plus(Duration.ofHours(2));

    private AppClock clock;

    @BeforeEach
    void setUp() {
        clock = new AppClock();
        clock.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ── Test 1: correctDirectionHit ───────────────────────────────────────────

    @Test
    void correctDirectionHit() {
        Alert alert = priceMovementAlert("alert-1", "mkt-1", "up", T);

        TestableEvaluator ev = evaluatorWith(alert);
        ev.addSnapshot("mkt-1", T, "0.50");
        ev.addSnapshot("mkt-1", T.plus(Duration.ofHours(1)), "0.55");

        ev.evaluate();

        AlertOutcome outcome = ev.getOutcome("alert-1", "t1h");
        assertThat(outcome).isNotNull();
        assertThat(outcome.getPriceAtAlert()).isEqualByComparingTo("0.50");
        assertThat(outcome.getPriceAtHorizon()).isEqualByComparingTo("0.55");
        assertThat(outcome.getMagnitudePp()).isEqualByComparingTo("0.05");
        assertThat(outcome.getDirectionRealized()).isEqualTo("up");
        assertThat(outcome.getWasCorrect()).isTrue();
    }

    // ── Test 2: wrongDirectionHit ─────────────────────────────────────────────

    @Test
    void wrongDirectionHit() {
        Alert alert = priceMovementAlert("alert-2", "mkt-2", "up", T);

        TestableEvaluator ev = evaluatorWith(alert);
        ev.addSnapshot("mkt-2", T, "0.50");
        ev.addSnapshot("mkt-2", T.plus(Duration.ofHours(1)), "0.45");

        ev.evaluate();

        AlertOutcome outcome = ev.getOutcome("alert-2", "t1h");
        assertThat(outcome).isNotNull();
        assertThat(outcome.getMagnitudePp()).isEqualByComparingTo("-0.05");
        assertThat(outcome.getDirectionRealized()).isEqualTo("down");
        assertThat(outcome.getWasCorrect()).isFalse();
    }

    // ── Test 3: deadZoneFlat ──────────────────────────────────────────────────

    @Test
    void deadZoneFlat() {
        Alert alert = priceMovementAlert("alert-3", "mkt-3", "up", T);

        TestableEvaluator ev = evaluatorWith(alert);
        ev.addSnapshot("mkt-3", T, "0.50");
        ev.addSnapshot("mkt-3", T.plus(Duration.ofHours(1)), "0.503");

        ev.evaluate();

        AlertOutcome outcome = ev.getOutcome("alert-3", "t1h");
        assertThat(outcome).isNotNull();
        assertThat(outcome.getMagnitudePp()).isEqualByComparingTo("0.003");
        assertThat(outcome.getDirectionRealized()).isEqualTo("flat");
        assertThat(outcome.getWasCorrect()).isNull();
    }

    // ── Test 4: missingSnapshot ───────────────────────────────────────────────

    @Test
    void missingSnapshot_horizonSkipped() {
        Alert alert = priceMovementAlert("alert-4", "mkt-4", "up", T);

        TestableEvaluator ev = evaluatorWith(alert);
        ev.addSnapshot("mkt-4", T, "0.50");
        // no snapshot at T+1h

        ev.evaluate();

        // t15m might fire if there's a snapshot at T+15min... there isn't, so skip both
        assertThat(ev.writtenOutcomes).isEmpty();
    }

    // ── Test 5: idempotentRerun ───────────────────────────────────────────────

    @Test
    void idempotentRerun_outcomeExistsAlready_writeNotCalled() {
        Alert alert = priceMovementAlert("alert-5", "mkt-5", "up", T);

        TestableEvaluator ev = evaluatorWith(alert);
        ev.addSnapshot("mkt-5", T, "0.50");
        ev.addSnapshot("mkt-5", T.plus(Duration.ofMinutes(15)), "0.55");
        ev.addSnapshot("mkt-5", T.plus(Duration.ofHours(1)), "0.55");
        // Mark all horizons as already evaluated
        ev.existingOutcomes.add("alert-5|t15m");
        ev.existingOutcomes.add("alert-5|t1h");
        ev.existingOutcomes.add("alert-5|t24h");

        ev.evaluate();

        assertThat(ev.writtenOutcomes).isEmpty();
    }

    // ── Test 6: resolutionCorrect ─────────────────────────────────────────────

    @Test
    void resolutionCorrect() {
        Alert alert = priceMovementAlert("alert-6", "mkt-6", "up", T);
        BigDecimal priceAtAlert   = new BigDecimal("0.50");
        BigDecimal resolutionPrice = new BigDecimal("1.0");

        AlertOutcomeEvaluator ev = new TestableEvaluator(clock, List.of());

        AlertOutcome outcome = ev.computeOutcome(
                "alert-6", "price_movement", "mkt-6",
                T, priceAtAlert, resolutionPrice,
                "up", "resolution", NOW, Map.of());

        assertThat(outcome.getHorizon()).isEqualTo("resolution");
        assertThat(outcome.getPriceAtHorizon()).isEqualByComparingTo("1.0");
        assertThat(outcome.getMagnitudePp()).isEqualByComparingTo("0.50");
        assertThat(outcome.getWasCorrect()).isTrue();
    }

    // ── Test 6b: resolutionHorizon_bugScenario ───────────────────────────────
    //
    // The real-world failure: currentYesPrice is already 1.0 by the time
    // ResolutionSweeper runs, so priceAtAlert = priceAtHorizon = 1.0 → rawDelta = 0
    // → old code gave "flat". New code must give "up" from priceAtHorizon alone.

    @Test
    void resolutionHorizon_resolvedPrice_givesUpNotFlat() {
        AlertOutcomeEvaluator ev = new TestableEvaluator(clock, List.of());

        // Bug scenario: priceAtAlert already reflects the resolved price (1.0)
        AlertOutcome outcome = ev.computeOutcome(
                "alert-bug", "price_movement", "mkt-yes",
                T, new BigDecimal("1.0"), new BigDecimal("1.0"),
                "up", "resolution", NOW, Map.of());

        assertThat(outcome.getDirectionRealized())
                .as("rawDelta=0 must not produce flat for resolution horizon")
                .isEqualTo("up");
        assertThat(outcome.getWasCorrect()).isTrue();
    }

    @Test
    void resolutionHorizon_noResolved_givesDownNotFlat() {
        AlertOutcomeEvaluator ev = new TestableEvaluator(clock, List.of());

        // NO-resolved market: priceAtAlert=0.0, priceAtHorizon=0.0 → rawDelta=0
        AlertOutcome outcome = ev.computeOutcome(
                "alert-no", "price_movement", "mkt-no",
                T, new BigDecimal("0.0"), new BigDecimal("0.0"),
                "down", "resolution", NOW, Map.of());

        assertThat(outcome.getDirectionRealized())
                .as("rawDelta=0 must not produce flat for NO-resolved resolution horizon")
                .isEqualTo("down");
        assertThat(outcome.getWasCorrect()).isTrue();
    }

    @Test
    void resolutionHorizon_nullPrediction_stillResolvesDirection() {
        AlertOutcomeEvaluator ev = new TestableEvaluator(clock, List.of());

        AlertOutcome outcome = ev.computeOutcome(
                "alert-null", "news_correlation", "mkt-yes",
                T, new BigDecimal("1.0"), new BigDecimal("1.0"),
                null, "resolution", NOW, Map.of());

        assertThat(outcome.getDirectionRealized()).isEqualTo("up");
        assertThat(outcome.getWasCorrect()).isNull(); // no prediction → no correctness score
    }

    // ── Test 7: resolutionWrong ───────────────────────────────────────────────

    @Test
    void resolutionWrong() {
        BigDecimal priceAtAlert    = new BigDecimal("0.50");
        BigDecimal resolutionPrice = new BigDecimal("1.0");

        AlertOutcomeEvaluator ev = new TestableEvaluator(clock, List.of());

        AlertOutcome outcome = ev.computeOutcome(
                "alert-7", "price_movement", "mkt-7",
                T, priceAtAlert, resolutionPrice,
                "down", "resolution", NOW, Map.of());

        // rawDelta = 1.0 - 0.5 = +0.50 (price went UP)
        // directionPredicted = "down" → magnitudePp = -rawDelta = -0.50
        assertThat(outcome.getMagnitudePp()).isEqualByComparingTo("-0.50");
        assertThat(outcome.getDirectionRealized()).isEqualTo("up");
        assertThat(outcome.getWasCorrect()).isFalse();
    }

    // ── Test 8: newsCorrelationNullDirection ──────────────────────────────────

    @Test
    void newsCorrelationNullDirection() {
        Alert alert = newsCorrelationAlert("alert-8", "mkt-8", T);

        TestableEvaluator ev = evaluatorWith(alert);
        ev.addSnapshot("mkt-8", T, "0.50");
        ev.addSnapshot("mkt-8", T.plus(Duration.ofHours(1)), "0.55");

        ev.evaluate();

        AlertOutcome outcome = ev.getOutcome("alert-8", "t1h");
        assertThat(outcome).isNotNull();
        assertThat(outcome.getDirectionPredicted()).isNull();
        assertThat(outcome.getDirectionRealized()).isNull();
        assertThat(outcome.getWasCorrect()).isNull();
        // magnitudePp = abs(rawDelta) = abs(0.05) = 0.05
        assertThat(outcome.getMagnitudePp()).isEqualByComparingTo("0.05");
    }

    // ── Test 9: detectedAtFallback ────────────────────────────────────────────

    @Test
    void detectedAtFallback_usesCreatedAt_warnLogged() {
        // Alert metadata has no detectedAt key. createdAt = T.
        Alert alert = new Alert();
        alert.setAlertId("alert-9");
        alert.setCreatedAt(T.toString());
        alert.setType("price_movement");
        alert.setMarketId("mkt-9");
        alert.setMetadata(Map.of("direction", "up")); // no detectedAt

        TestableEvaluator ev = evaluatorWith(alert);
        // Seed snapshot at T (the createdAt timestamp, which should be used as firedAt fallback)
        ev.addSnapshot("mkt-9", T, "0.50");
        ev.addSnapshot("mkt-9", T.plus(Duration.ofHours(1)), "0.55");

        ev.evaluate();

        // Verify a snapshot lookup happened at T (createdAt fallback)
        assertThat(ev.lookupTargets).isNotEmpty();
        // First lookup target should be T (the fallback firedAt)
        assertThat(ev.lookupTargets.get(0)).isEqualTo(T);

        // Outcome should still be computed correctly
        AlertOutcome outcome = ev.getOutcome("alert-9", "t1h");
        assertThat(outcome).isNotNull();
        assertThat(outcome.getWasCorrect()).isTrue();
    }

    // ── Test 10: marketWithNoSnapshots ────────────────────────────────────────

    @Test
    void marketWithNoSnapshots_horizonSkipped_noException() {
        Alert alert = priceMovementAlert("alert-10", "mkt-10", "up", T);

        TestableEvaluator ev = evaluatorWith(alert);
        // no snapshots seeded at all

        // Must not throw
        ev.evaluate();

        assertThat(ev.writtenOutcomes).isEmpty();
    }

    // ── Test 11: downPredictionCorrect ───────────────────────────────────────

    @Test
    void downPredictionCorrect() {
        // statistical_anomaly with negative lastReturn → direction="down"
        Alert alert = statisticalAnomalyAlert("alert-11", "mkt-11", "down", T);

        TestableEvaluator ev = evaluatorWith(alert);
        ev.addSnapshot("mkt-11", T, "0.50");
        ev.addSnapshot("mkt-11", T.plus(Duration.ofHours(1)), "0.42");

        ev.evaluate();

        AlertOutcome outcome = ev.getOutcome("alert-11", "t1h");
        assertThat(outcome).isNotNull();
        // rawDelta = 0.42 - 0.50 = -0.08; directionPredicted="down" → magnitudePp = -rawDelta = +0.08
        assertThat(outcome.getMagnitudePp()).isEqualByComparingTo("0.08");
        assertThat(outcome.getDirectionRealized()).isEqualTo("down");
        assertThat(outcome.getWasCorrect()).isTrue();
    }

    // ── Test 13: nullPriceAtAlert → scorable=false ───────────────────────────

    @Test
    void nullPriceAtAlert_outcomeIsUnscorable() {
        AlertOutcomeEvaluator ev = new TestableEvaluator(clock, List.of());

        AlertOutcome outcome = ev.computeOutcome(
                "alert-13", "price_movement", "mkt-13",
                T, null /* priceAtAlert */, new BigDecimal("1.0"),
                "up", "resolution", NOW, Map.of());

        assertThat(outcome.getScorable()).isFalse();
        assertThat(outcome.getSkipReason()).isEqualTo("no_baseline");
        assertThat(outcome.getWasCorrect()).isNull();
        assertThat(outcome.getDirectionRealized()).isNull();
        assertThat(outcome.getBrierSkill()).isNull();
    }

    // ── Test 14: deadZone flagged ─────────────────────────────────────────────

    @Test
    void lowPriceAtAlert_deadZoneTrue() {
        AlertOutcomeEvaluator ev = new TestableEvaluator(clock, List.of());

        // priceAtAlert = 0.08 < 0.10 → dead zone
        AlertOutcome outcome = ev.computeOutcome(
                "alert-14", "price_movement", "mkt-14",
                T, new BigDecimal("0.08"), new BigDecimal("1.0"),
                "up", "resolution", NOW, Map.of());

        assertThat(outcome.getScorable()).isTrue();
        assertThat(outcome.getDeadZone()).isTrue();
    }

    // ── Test 15: Brier skill — UP at 0.30, resolves YES ──────────────────────
    // detectorProbYes = min(0.30 + 0.20, 0.99) = 0.50
    // marketProbYes   = 0.30
    // actual          = 1.0  (priceAtHorizon=1.0 ≥ 0.50)
    // marketBrier     = (0.30 - 1.0)² = 0.49
    // detectorBrier   = (0.50 - 1.0)² = 0.25
    // brierSkill      = 0.49 - 0.25   = +0.24

    @Test
    void brierSkill_upAtThirty_resolvesYes_positiveSkill() {
        AlertOutcomeEvaluator ev = new TestableEvaluator(clock, List.of());

        AlertOutcome outcome = ev.computeOutcome(
                "alert-15", "price_movement", "mkt-15",
                T, new BigDecimal("0.30"), new BigDecimal("1.0"),
                "up", "resolution", NOW, Map.of());

        assertThat(outcome.getScorable()).isTrue();
        assertThat(outcome.getMarketBrier().doubleValue()).isCloseTo(0.49, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(outcome.getDetectorBrier().doubleValue()).isCloseTo(0.25, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(outcome.getBrierSkill().doubleValue()).isCloseTo(0.24, org.assertj.core.api.Assertions.within(1e-9));
    }

    // ── Test 16: Brier skill — UP at 0.30, resolves NO ───────────────────────
    // detectorProbYes = 0.50
    // marketProbYes   = 0.30
    // actual          = 0.0  (priceAtHorizon=0.0 < 0.50)
    // marketBrier     = (0.30 - 0.0)² = 0.09
    // detectorBrier   = (0.50 - 0.0)² = 0.25
    // brierSkill      = 0.09 - 0.25   = -0.16

    @Test
    void brierSkill_upAtThirty_resolvesNo_negativeSkill() {
        AlertOutcomeEvaluator ev = new TestableEvaluator(clock, List.of());

        AlertOutcome outcome = ev.computeOutcome(
                "alert-16", "price_movement", "mkt-16",
                T, new BigDecimal("0.30"), new BigDecimal("0.0"),
                "up", "resolution", NOW, Map.of());

        assertThat(outcome.getScorable()).isTrue();
        assertThat(outcome.getMarketBrier().doubleValue()).isCloseTo(0.09, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(outcome.getDetectorBrier().doubleValue()).isCloseTo(0.25, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(outcome.getBrierSkill().doubleValue()).isCloseTo(-0.16, org.assertj.core.api.Assertions.within(1e-9));
    }

    // ── Test 12: categoryClassifierFallback ──────────────────────────────────

    @Test
    void categoryClassifierFallback_dynamoNull_returnsCategoryClassifierResult() {
        // marketsTable is null in the test constructor, simulating a DynamoDB miss.
        // CategoryClassifier should classify the title as "crypto".
        Alert alert = new Alert();
        alert.setAlertId("alert-12");
        alert.setCreatedAt(T.toString());
        alert.setType("price_movement");
        alert.setMarketId("market-btc-1");
        alert.setTitle("Will Bitcoin reach $100k by end of 2025?");
        Map<String, String> meta = new HashMap<>();
        meta.put("direction", "up");
        meta.put("detectedAt", T.toString());
        alert.setMetadata(meta);

        TestableEvaluator ev = evaluatorWith(alert);
        ev.addSnapshot("market-btc-1", T, "0.50");
        ev.addSnapshot("market-btc-1", T.plus(Duration.ofHours(1)), "0.60");

        ev.evaluate();

        AlertOutcome outcome = ev.getOutcome("alert-12", "t1h");
        assertThat(outcome).isNotNull();
        assertThat(outcome.getCategory()).isEqualTo("crypto");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TestableEvaluator evaluatorWith(Alert... alerts) {
        return new TestableEvaluator(clock, List.of(alerts));
    }

    private static Alert priceMovementAlert(String alertId, String marketId,
                                             String direction, Instant detectedAt) {
        Alert a = new Alert();
        a.setAlertId(alertId);
        a.setCreatedAt(detectedAt.toString());
        a.setType("price_movement");
        a.setMarketId(marketId);
        Map<String, String> meta = new HashMap<>();
        meta.put("direction", direction);
        meta.put("detectedAt", detectedAt.toString());
        a.setMetadata(meta);
        return a;
    }

    private static Alert statisticalAnomalyAlert(String alertId, String marketId,
                                                   String direction, Instant detectedAt) {
        Alert a = new Alert();
        a.setAlertId(alertId);
        a.setCreatedAt(detectedAt.toString());
        a.setType("statistical_anomaly");
        a.setMarketId(marketId);
        Map<String, String> meta = new HashMap<>();
        meta.put("direction", direction);
        meta.put("detectedAt", detectedAt.toString());
        a.setMetadata(meta);
        return a;
    }

    private static Alert newsCorrelationAlert(String alertId, String marketId, Instant createdAt) {
        Alert a = new Alert();
        a.setAlertId(alertId);
        a.setCreatedAt(createdAt.toString());
        a.setType("news_correlation");
        a.setMarketId(marketId);
        // news_correlation has no direction and no detectedAt in metadata (by design)
        a.setMetadata(Map.of("score", "0.75"));
        return a;
    }

    private static PriceSnapshot snap(String marketId, Instant ts, String price) {
        PriceSnapshot s = new PriceSnapshot();
        s.setMarketId(marketId);
        s.setTimestamp(ts.toString());
        s.setMidpoint(new BigDecimal(price));
        return s;
    }

    // ── Testable subclass ─────────────────────────────────────────────────────

    private static class TestableEvaluator extends AlertOutcomeEvaluator {

        private final List<Alert> alertsToReturn;
        // key = "marketId|timestamp"
        private final Map<String, BigDecimal> priceMap = new HashMap<>();
        final List<AlertOutcome> writtenOutcomes = new ArrayList<>();
        final Set<String> existingOutcomes = new HashSet<>();
        // records every Instant passed to findClosestSnapshot
        final List<Instant> lookupTargets = new ArrayList<>();

        TestableEvaluator(AppClock clock, List<Alert> alerts) {
            super(clock);
            this.alertsToReturn = alerts;
        }

        void addSnapshot(String marketId, Instant ts, String price) {
            priceMap.put(marketId + "|" + ts.toString(), new BigDecimal(price));
        }

        AlertOutcome getOutcome(String alertId, String horizon) {
            return writtenOutcomes.stream()
                    .filter(o -> alertId.equals(o.getAlertId()) && horizon.equals(o.getHorizon()))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        List<Alert> scanRecentAlerts(Instant earliest, Instant latest) {
            return alertsToReturn;
        }

        @Override
        Optional<PriceSnapshot> findClosestSnapshot(String marketId, Instant target) {
            lookupTargets.add(target);
            // Find the entry whose timestamp is closest to target within ±2min
            Instant windowStart = target.minusSeconds(120);
            Instant windowEnd   = target.plusSeconds(120);

            return priceMap.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(marketId + "|"))
                    .filter(e -> {
                        Instant ts = Instant.parse(e.getKey().substring(marketId.length() + 1));
                        return !ts.isBefore(windowStart) && !ts.isAfter(windowEnd);
                    })
                    .min((a, b) -> {
                        Instant tsA = Instant.parse(a.getKey().substring(marketId.length() + 1));
                        Instant tsB = Instant.parse(b.getKey().substring(marketId.length() + 1));
                        long diffA = Math.abs(tsA.getEpochSecond() - target.getEpochSecond());
                        long diffB = Math.abs(tsB.getEpochSecond() - target.getEpochSecond());
                        return Long.compare(diffA, diffB);
                    })
                    .map(e -> {
                        Instant ts = Instant.parse(e.getKey().substring(marketId.length() + 1));
                        PriceSnapshot s = new PriceSnapshot();
                        s.setMarketId(marketId);
                        s.setTimestamp(ts.toString());
                        s.setMidpoint(e.getValue());
                        return s;
                    });
        }

        @Override
        void writeOutcome(AlertOutcome outcome) {
            writtenOutcomes.add(outcome);
        }

        @Override
        boolean outcomeExists(String alertId, String horizon) {
            return existingOutcomes.contains(alertId + "|" + horizon);
        }
    }
}
