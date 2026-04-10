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
        BigDecimal resolutionPrice = BigDecimal.ONE; // YES resolved = 1.0

        TestableSweeper sweeper = new TestableSweeper(clock, evaluator);
        sweeper.closedMarkets.add(new ResolutionSweeper.ClosedMarket("mkt-r1", "0.50", resolutionPrice));
        sweeper.alertsByMarket.put("mkt-r1", List.of(alert));

        sweeper.sweep();

        assertThat(sweeper.writtenOutcomes).hasSize(1);
        AlertOutcome outcome = sweeper.writtenOutcomes.get(0);
        assertThat(outcome.getHorizon()).isEqualTo("resolution");
        assertThat(outcome.getPriceAtHorizon()).isEqualByComparingTo("1.0");
        assertThat(outcome.getMagnitudePp()).isEqualByComparingTo("0.50");  // rawDelta=+0.50, direction=up
        assertThat(outcome.getWasCorrect()).isTrue();
    }

    // ── Test 2: down-alert on YES-resolved market → wrong ────────────────────

    @Test
    void closedMarket_downAlert_resolutionRowWrong() {
        Alert alert = priceMovementAlert("alert-r2", "mkt-r2", "down", T);
        BigDecimal resolutionPrice = BigDecimal.ONE; // YES resolved = 1.0

        TestableSweeper sweeper = new TestableSweeper(clock, evaluator);
        sweeper.closedMarkets.add(new ResolutionSweeper.ClosedMarket("mkt-r2", "0.50", resolutionPrice));
        sweeper.alertsByMarket.put("mkt-r2", List.of(alert));

        sweeper.sweep();

        assertThat(sweeper.writtenOutcomes).hasSize(1);
        AlertOutcome outcome = sweeper.writtenOutcomes.get(0);
        // rawDelta = 1.0 - 0.50 = +0.50; direction="down" → magnitudePp = -rawDelta = -0.50
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

        final List<ResolutionSweeper.ClosedMarket> closedMarkets = new ArrayList<>();
        final Map<String, List<Alert>> alertsByMarket = new HashMap<>();
        final List<AlertOutcome> writtenOutcomes = new ArrayList<>();

        TestableSweeper(AppClock clock, AlertOutcomeEvaluator evaluator) {
            super(clock, evaluator);
        }

        @Override
        List<ResolutionSweeper.ClosedMarket> findClosedMarkets() {
            return closedMarkets;
        }

        @Override
        List<Alert> findAlertsForMarket(String marketId) {
            return alertsByMarket.getOrDefault(marketId, List.of());
        }

        @Override
        void writeResolutionOutcome(AlertOutcome outcome) {
            writtenOutcomes.add(outcome);
        }
    }
}
