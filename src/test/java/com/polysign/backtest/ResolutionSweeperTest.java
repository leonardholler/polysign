package com.polysign.backtest;

import com.polysign.common.AppClock;
import com.polysign.model.Alert;
import com.polysign.model.AlertOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ResolutionSweeper}.
 *
 * Uses a testable subclass that stubs findClosedMarkets(), findAlertsForMarket(),
 * and writeResolutionOutcome() — no DynamoDB required.
 */
class ResolutionSweeperTest {

    private static final Instant T   = Instant.parse("2026-04-09T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-04-09T18:00:00Z");

    private AppClock clock;
    private AlertOutcomeEvaluator evaluator;

    @BeforeEach
    void setUp() {
        clock = new AppClock();
        clock.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        evaluator = new AlertOutcomeEvaluator(clock);
    }

    // ── Test 1: up-alert on YES-resolved market ───────────────────────────────

    @Test
    void closedMarket_upAlert_resolutionRowCorrect() {
        Alert alert = priceMovementAlert("alert-r1", "mkt-r1", "up", T);
        alert.setPriceAtAlert(new BigDecimal("0.50")); // pre-move price stored at fire time
        BigDecimal resolutionPrice = BigDecimal.ONE; // YES resolved = 1.0

        TestableSweeper sweeper = new TestableSweeper(clock, evaluator);
        sweeper.closedMarkets.add(new ResolutionSweeper.ClosedMarket("mkt-r1", "0.99", resolutionPrice));
        sweeper.alertsByMarket.put("mkt-r1", List.of(alert));

        sweeper.sweep();

        assertThat(sweeper.writtenOutcomes).hasSize(1);
        AlertOutcome outcome = sweeper.writtenOutcomes.get(0);
        assertThat(outcome.getHorizon()).isEqualTo("resolution");
        assertThat(outcome.getPriceAtHorizon()).isEqualByComparingTo("1.0");
        assertThat(outcome.getPriceAtAlert()).isEqualByComparingTo("0.50"); // from alert, not cm
        assertThat(outcome.getMagnitudePp()).isEqualByComparingTo("0.50");  // rawDelta=+0.50, direction=up
        assertThat(outcome.getWasCorrect()).isTrue();
    }

    // ── Test 2: down-alert on YES-resolved market → wrong ────────────────────

    @Test
    void closedMarket_downAlert_resolutionRowWrong() {
        Alert alert = priceMovementAlert("alert-r2", "mkt-r2", "down", T);
        alert.setPriceAtAlert(new BigDecimal("0.50")); // pre-move price stored at fire time
        BigDecimal resolutionPrice = BigDecimal.ONE; // YES resolved = 1.0

        TestableSweeper sweeper = new TestableSweeper(clock, evaluator);
        sweeper.closedMarkets.add(new ResolutionSweeper.ClosedMarket("mkt-r2", "0.99", resolutionPrice));
        sweeper.alertsByMarket.put("mkt-r2", List.of(alert));

        sweeper.sweep();

        assertThat(sweeper.writtenOutcomes).hasSize(1);
        AlertOutcome outcome = sweeper.writtenOutcomes.get(0);
        // priceAtAlert=0.50 (from alert), rawDelta = 1.0 - 0.50 = +0.50
        // direction="down" → magnitudePp = -rawDelta = -0.50
        assertThat(outcome.getMagnitudePp()).isEqualByComparingTo("-0.50");
        assertThat(outcome.getDirectionRealized()).isEqualTo("up");
        assertThat(outcome.getWasCorrect()).isFalse();
    }

    // ── Test 3: market not closed → no resolution row ─────────────────────────

    @Test
    void marketNotClosed_noResolutionRowWritten() {
        TestableSweeper sweeper = new TestableSweeper(clock, evaluator);
        // no closedMarkets seeded → sweep finds nothing

        sweeper.sweep();

        assertThat(sweeper.writtenOutcomes).isEmpty();
    }

    // ── Test 4: effectivelyResolved path writes resolution row ────────────────

    @Test
    void effectiveResolution_upAlert_resolutionRowCorrect() {
        Alert alert = priceMovementAlert("alert-eff1", "mkt-eff1", "up", T);
        alert.setPriceAtAlert(new BigDecimal("0.40")); // pre-move price stored at fire time
        BigDecimal resolutionPrice = BigDecimal.ONE; // YES resolved

        TestableSweeper sweeper = new TestableSweeper(clock, evaluator);
        // No formal closedMarket — only effectivelyResolved
        sweeper.effectiveMarkets.add(new ResolutionSweeper.ClosedMarket("mkt-eff1", "0.99", resolutionPrice));
        sweeper.alertsByMarket.put("mkt-eff1", List.of(alert));

        sweeper.sweep();

        assertThat(sweeper.writtenOutcomes).hasSize(1);
        AlertOutcome outcome = sweeper.writtenOutcomes.get(0);
        assertThat(outcome.getHorizon()).isEqualTo("resolution");
        assertThat(outcome.getPriceAtHorizon()).isEqualByComparingTo("1.0");
        assertThat(outcome.getPriceAtAlert()).isEqualByComparingTo("0.40"); // from alert, not cm
        assertThat(outcome.getWasCorrect()).isTrue();
    }

    // ── Test 5 (e): idempotency — second write for same (alertId, resolution) ─

    @Test
    void effectiveResolution_secondSweep_doesNotWriteDuplicate() {
        Alert alert = priceMovementAlert("alert-idem1", "mkt-idem1", "up", T);
        alert.setPriceAtAlert(new BigDecimal("0.50"));
        BigDecimal resolutionPrice = BigDecimal.ONE;

        TestableSweeper sweeper = new TestableSweeper(clock, evaluator);
        sweeper.effectiveMarkets.add(new ResolutionSweeper.ClosedMarket("mkt-idem1", "0.99", resolutionPrice));
        sweeper.alertsByMarket.put("mkt-idem1", List.of(alert));

        // First sweep — outcome is written
        sweeper.sweep();
        assertThat(sweeper.writtenOutcomes).hasSize(1);

        // Second sweep — resolutionOutcomeExists returns true for the written alert,
        // so writeResolutionOutcome is NOT called again
        sweeper.sweep();
        assertThat(sweeper.writtenOutcomes).hasSize(1); // still 1, no duplicate
    }

    // ── Test 6: Phase A takes precedence over Phase B ─────────────────────────

    @Test
    void phaseA_takesPresedenceOverPhaseB_sameMarket() {
        Alert alert = priceMovementAlert("alert-prec1", "mkt-prec1", "down", T);
        alert.setPriceAtAlert(new BigDecimal("0.50"));
        // Phase A sees YES resolved (price = 1.0)
        BigDecimal formalPrice    = BigDecimal.ONE;
        // Phase B also fires with the same derived price — but should be skipped
        BigDecimal effectivePrice = BigDecimal.ONE;

        TestableSweeper sweeper = new TestableSweeper(clock, evaluator);
        sweeper.closedMarkets.add(new ResolutionSweeper.ClosedMarket("mkt-prec1", "0.99", formalPrice));
        sweeper.effectiveMarkets.add(new ResolutionSweeper.ClosedMarket("mkt-prec1", "0.99", effectivePrice));
        sweeper.alertsByMarket.put("mkt-prec1", List.of(alert));

        sweeper.sweep();

        // Phase A writes one outcome; Phase B sees it exists and skips
        assertThat(sweeper.writtenOutcomes).hasSize(1);
        assertThat(sweeper.writtenOutcomes.get(0).getAlertId()).isEqualTo("alert-prec1");
    }

    // ── Test 7: resolution-tick price (cm) does NOT overwrite correct priceAtAlert ───

    @Test
    void priceAtAlert_fromAlert_notFromResolutionTickPrice() {
        // Alert has priceAtAlert=0.40 (the pre-move price, set at fire time).
        // cm has a resolution-tick price of 0.0065 (market resolved NO at sweep time).
        // The sweeper must use alert.getPriceAtAlert() = 0.40, ignoring cm entirely.
        Alert alert = priceMovementAlert("alert-tick1", "mkt-tick1", "up", T);
        alert.setPriceAtAlert(new BigDecimal("0.40"));
        BigDecimal resolutionTickPrice = new BigDecimal("0.0065"); // market resolved NO

        TestableSweeper sweeper = new TestableSweeper(clock, evaluator);
        sweeper.closedMarkets.add(new ResolutionSweeper.ClosedMarket("mkt-tick1", "0.0065", resolutionTickPrice));
        sweeper.alertsByMarket.put("mkt-tick1", List.of(alert));

        sweeper.sweep();

        assertThat(sweeper.writtenOutcomes).hasSize(1);
        AlertOutcome outcome = sweeper.writtenOutcomes.get(0);
        assertThat(outcome.getPriceAtAlert())
                .as("priceAtAlert must come from alert row (0.40), not the resolution-tick cm price (0.0065)")
                .isEqualByComparingTo("0.40");
        // magnitudePp = priceAtHorizon - priceAtAlert = 0.0065 - 0.40 = -0.3935; direction=up → negative
        assertThat(outcome.getMagnitudePp()).isEqualByComparingTo("-0.3935");
        assertThat(outcome.getWasCorrect()).isFalse(); // predicted up, realized down
    }

    // ── Test 8: pre-deploy alert (null priceAtAlert) → outcome.priceAtAlert is null ─

    @Test
    void priceAtAlert_isNull_forPreDeployAlert() {
        // Pre-deploy alert: priceAtAlert was never set (null).
        // cm has a resolution-tick price of 0.9945 (market resolved YES).
        // The sweeper must leave priceAtAlert null — no fallback to cm.
        Alert alert = priceMovementAlert("alert-pre1", "mkt-pre1", "up", T);
        // intentionally NOT calling alert.setPriceAtAlert(...) → stays null
        BigDecimal resolutionTickPrice = new BigDecimal("0.9945"); // market resolved YES

        TestableSweeper sweeper = new TestableSweeper(clock, evaluator);
        sweeper.closedMarkets.add(new ResolutionSweeper.ClosedMarket("mkt-pre1", "0.9945", resolutionTickPrice));
        sweeper.alertsByMarket.put("mkt-pre1", List.of(alert));

        sweeper.sweep();

        assertThat(sweeper.writtenOutcomes).hasSize(1);
        AlertOutcome outcome = sweeper.writtenOutcomes.get(0);
        assertThat(outcome.getPriceAtAlert())
                .as("priceAtAlert must be null for pre-deploy alerts — no fallback to cm resolution-tick price")
                .isNull();
        // magnitudePp is null when priceAtAlert is null (null-safe evaluator)
        assertThat(outcome.getMagnitudePp()).isNull();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    // ── Testable subclass ─────────────────────────────────────────────────────

    private static class TestableSweeper extends ResolutionSweeper {

        final List<ResolutionSweeper.ClosedMarket> closedMarkets    = new ArrayList<>();
        final List<ResolutionSweeper.ClosedMarket> effectiveMarkets = new ArrayList<>();
        final Map<String, List<Alert>>             alertsByMarket   = new HashMap<>();
        final List<AlertOutcome>                   writtenOutcomes  = new ArrayList<>();

        TestableSweeper(AppClock clock, AlertOutcomeEvaluator evaluator) {
            super(clock, evaluator);
        }

        @Override
        List<ResolutionSweeper.ClosedMarket> findClosedMarkets() {
            return closedMarkets;
        }

        @Override
        List<ResolutionSweeper.ClosedMarket> findEffectivelyResolvedMarkets() {
            return effectiveMarkets;
        }

        @Override
        List<Alert> findAlertsForMarket(String marketId) {
            return alertsByMarket.getOrDefault(marketId, List.of());
        }

        @Override
        void writeResolutionOutcome(AlertOutcome outcome) {
            writtenOutcomes.add(outcome);
        }

        /**
         * Mirrors production logic: returns true if this alert already has a written outcome.
         * Enables idempotency testing without a live DynamoDB table.
         */
        @Override
        boolean resolutionOutcomeExists(String alertId) {
            return writtenOutcomes.stream().anyMatch(o -> o.getAlertId().equals(alertId));
        }
    }
}
