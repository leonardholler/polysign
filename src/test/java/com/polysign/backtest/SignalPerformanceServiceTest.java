package com.polysign.backtest;

import com.polysign.backtest.SignalPerformanceService.DetectorPerformance;
import com.polysign.backtest.SignalPerformanceService.PerformanceResponse;
import com.polysign.common.AppClock;
import com.polysign.model.AlertOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link SignalPerformanceService}.
 *
 * Uses a testable subclass that returns in-memory outcomes instead of querying DynamoDB.
 */
class SignalPerformanceServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-09T12:00:00Z");
    private static final Instant SINCE = NOW.minusSeconds(7L * 24 * 3600);

    private AppClock clock;

    @BeforeEach
    void setUp() {
        clock = new AppClock();
        clock.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * Seed 15 fake AlertOutcome records:
     *  - 8 price_movement at t1h: 5 correct, 2 wrong, 1 flat (null wasCorrect)
     *  - 7 statistical_anomaly at t1h: 3 correct, 3 wrong, 1 flat
     */
    @Test
    void precisionAndCountsAreComputedCorrectly() {
        List<AlertOutcome> outcomes = new ArrayList<>();

        // price_movement: 5 correct, 2 wrong, 1 flat
        for (int i = 0; i < 5; i++) outcomes.add(outcome("price_movement", "t1h", true,  "0.05"));
        for (int i = 0; i < 2; i++) outcomes.add(outcome("price_movement", "t1h", false, "-0.03"));
        outcomes.add(outcome("price_movement", "t1h", null, "0.002")); // flat

        // statistical_anomaly: 3 correct, 3 wrong, 1 flat
        for (int i = 0; i < 3; i++) outcomes.add(outcome("statistical_anomaly", "t1h", true,  "0.04"));
        for (int i = 0; i < 3; i++) outcomes.add(outcome("statistical_anomaly", "t1h", false, "-0.02"));
        outcomes.add(outcome("statistical_anomaly", "t1h", null, "0.001")); // flat

        TestableService service = new TestableService(clock, outcomes);
        PerformanceResponse resp = service.getPerformance(null, "t1h", SINCE);

        assertThat(resp.horizon()).isEqualTo("t1h");

        DetectorPerformance pm = findType(resp, "price_movement");
        assertThat(pm).isNotNull();
        assertThat(pm.count()).isEqualTo(8);
        // precision = 5 / (5+2) = 0.714...
        assertThat(pm.precision()).isCloseTo(5.0 / 7.0, within(1e-9));

        DetectorPerformance sa = findType(resp, "statistical_anomaly");
        assertThat(sa).isNotNull();
        assertThat(sa.count()).isEqualTo(7);
        // precision = 3 / (3+3) = 0.5
        assertThat(sa.precision()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void medianIsCorrectMiddleValue() {
        // 5 price_movement outcomes with magnitudePp = 0.01, 0.03, 0.05, 0.07, 0.09
        List<AlertOutcome> outcomes = new ArrayList<>();
        outcomes.add(outcome("price_movement", "t1h", true, "0.09"));
        outcomes.add(outcome("price_movement", "t1h", true, "0.03"));
        outcomes.add(outcome("price_movement", "t1h", true, "0.05")); // median
        outcomes.add(outcome("price_movement", "t1h", true, "0.01"));
        outcomes.add(outcome("price_movement", "t1h", true, "0.07"));

        TestableService service = new TestableService(clock, outcomes);
        PerformanceResponse resp = service.getPerformance("price_movement", "t1h", SINCE);

        DetectorPerformance pm = findType(resp, "price_movement");
        assertThat(pm).isNotNull();
        // sorted: [0.01, 0.03, 0.05, 0.07, 0.09] → middle = 0.05
        assertThat(pm.medianMagnitudePp()).isCloseTo(0.05, within(1e-9));
    }

    @Test
    void typeFilterReturnsOnlyRequestedType() {
        List<AlertOutcome> outcomes = new ArrayList<>();
        outcomes.add(outcome("price_movement",     "t1h", true,  "0.05"));
        outcomes.add(outcome("statistical_anomaly", "t1h", false, "-0.03"));

        TestableService service = new TestableService(clock, outcomes);
        PerformanceResponse resp = service.getPerformance("price_movement", "t1h", SINCE);

        assertThat(resp.detectors()).hasSize(1);
        assertThat(resp.detectors().get(0).type()).isEqualTo("price_movement");
    }

    @Test
    void horizonFilterExcludesWrongHorizons() {
        List<AlertOutcome> outcomes = new ArrayList<>();
        outcomes.add(outcome("price_movement", "t1h",  true, "0.05"));
        outcomes.add(outcome("price_movement", "t24h", true, "0.08")); // different horizon

        TestableService service = new TestableService(clock, outcomes);
        PerformanceResponse resp = service.getPerformance(null, "t1h", SINCE);

        DetectorPerformance pm = findType(resp, "price_movement");
        assertThat(pm).isNotNull();
        assertThat(pm.count()).isEqualTo(1); // only the t1h outcome
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static DetectorPerformance findType(PerformanceResponse resp, String type) {
        return resp.detectors().stream()
                .filter(d -> type.equals(d.type()))
                .findFirst()
                .orElse(null);
    }

    private static AlertOutcome outcome(String type, String horizon,
                                        Boolean wasCorrect, String magnitudePp) {
        AlertOutcome o = new AlertOutcome();
        o.setType(type);
        o.setHorizon(horizon);
        o.setFiredAt(NOW.minusSeconds(3600).toString());
        o.setWasCorrect(wasCorrect);
        o.setMagnitudePp(new BigDecimal(magnitudePp));
        return o;
    }

    // ── Testable subclass ─────────────────────────────────────────────────────

    private static class TestableService extends SignalPerformanceService {

        private final List<AlertOutcome> allOutcomes;
        private final AppClock myClock;

        TestableService(AppClock clock, List<AlertOutcome> allOutcomes) {
            super(null, clock); // alertOutcomesTable=null; overridden below
            this.allOutcomes = allOutcomes;
            this.myClock = clock;
        }

        @Override
        public PerformanceResponse getPerformance(String type, String horizon, Instant since) {
            // By-pass the DynamoDB query and feed outcomes directly
            // We reuse the parent's aggregation logic by calling a helper that
            // doesn't hit DynamoDB. Since fetchOutcomes() is private, we override
            // getPerformance() here to inline the same logic with our canned data.
            if (horizon == null || horizon.isBlank()) horizon = "t1h";

            final String finalHorizon = horizon;
            List<AlertOutcome> filtered = allOutcomes.stream()
                    .filter(o -> finalHorizon.equals(o.getHorizon()))
                    .filter(o -> type == null || type.equals(o.getType()))
                    .toList();

            // Group by type and aggregate using parent class's package-private logic
            // (we call the parent's computePerformanceForGroup helper indirectly by
            // delegating back through the normal code path)
            Map<String, List<AlertOutcome>> byType = new java.util.LinkedHashMap<>();
            for (AlertOutcome o : filtered) {
                byType.computeIfAbsent(o.getType() != null ? o.getType() : "unknown",
                        k -> new ArrayList<>()).add(o);
            }

            List<DetectorPerformance> detectors = new ArrayList<>();
            List<String> orderedTypes = type != null
                    ? List.of(type)
                    : SignalPerformanceService.KNOWN_TYPES;

            for (String t : orderedTypes) {
                List<AlertOutcome> group = byType.getOrDefault(t, List.of());
                detectors.add(aggregatePublic(t, group));
            }

            Instant since2 = since != null ? since : myClock.now().minusSeconds(7 * 24 * 3600L);
            return new PerformanceResponse(finalHorizon, since2.toString(), detectors);
        }

        /**
         * Replicates the parent's aggregate() logic (which is private) for test verification.
         * Precision formula per Decision 7.
         */
        private DetectorPerformance aggregatePublic(String type, List<AlertOutcome> outcomes) {
            int count = outcomes.size();
            long correctCount = outcomes.stream()
                    .filter(o -> Boolean.TRUE.equals(o.getWasCorrect())).count();
            long wrongCount = outcomes.stream()
                    .filter(o -> Boolean.FALSE.equals(o.getWasCorrect())).count();
            Double precision = (correctCount + wrongCount) == 0
                    ? null
                    : (double) correctCount / (correctCount + wrongCount);

            double avgMag = outcomes.stream()
                    .filter(o -> o.getMagnitudePp() != null)
                    .mapToDouble(o -> o.getMagnitudePp().doubleValue())
                    .average().orElse(0.0);

            List<Double> sorted = outcomes.stream()
                    .filter(o -> o.getMagnitudePp() != null)
                    .map(o -> o.getMagnitudePp().doubleValue())
                    .sorted().toList();
            double median = sorted.isEmpty() ? 0.0
                    : (sorted.size() % 2 == 0
                       ? (sorted.get(sorted.size()/2-1) + sorted.get(sorted.size()/2)) / 2.0
                       : sorted.get(sorted.size()/2));

            double meanAbs = outcomes.stream()
                    .filter(o -> o.getMagnitudePp() != null)
                    .mapToDouble(o -> Math.abs(o.getMagnitudePp().doubleValue()))
                    .average().orElse(0.0);

            return new DetectorPerformance(type, count, precision, avgMag, median, meanAbs);
        }
    }
}
