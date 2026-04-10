package com.polysign.backtest;

import com.polysign.common.AppClock;
import com.polysign.model.AlertOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates {@code alert_outcomes} into per-detector precision / magnitude metrics.
 *
 * <p>Queries the {@code type-firedAt-index} GSI. When {@code type} is specified,
 * one GSI query with PK=type and SK≥since. When {@code type} is null, queries each
 * known detector type separately and merges the results. Horizon filtering is done
 * in-memory (the GSI does not filter by horizon).
 *
 * <p>Precision definition:
 * {@code precision = correctCount / (correctCount + wrongCount)}
 * where flat outcomes ({@code wasCorrect=null}) are excluded from both
 * numerator and denominator. Returns {@code null} when the denominator is 0.
 */
@Service
public class SignalPerformanceService {

    private static final Logger log = LoggerFactory.getLogger(SignalPerformanceService.class);

    static final List<String> KNOWN_TYPES = List.of(
            "price_movement", "statistical_anomaly", "consensus",
            "wallet_activity", "news_correlation");

    private final DynamoDbTable<AlertOutcome> alertOutcomesTable;
    private final AppClock clock;

    public SignalPerformanceService(DynamoDbTable<AlertOutcome> alertOutcomesTable, AppClock clock) {
        this.alertOutcomesTable = alertOutcomesTable;
        this.clock              = clock;
    }

    /**
     * Compute per-detector performance.
     *
     * @param type    optional detector type filter; null = all known types
     * @param horizon one of t15m, t1h, t24h, resolution (default t1h)
     * @param since   look back from this instant (default 7 days ago)
     * @return response record ready for JSON serialisation
     */
    public PerformanceResponse getPerformance(String type, String horizon, Instant since) {
        if (horizon == null || horizon.isBlank()) horizon = "t1h";
        if (since == null) since = clock.now().minus(Duration.ofDays(7));

        List<AlertOutcome> outcomes = fetchOutcomes(type, since);

        // Filter to the requested horizon in-memory
        final String finalHorizon = horizon;
        List<AlertOutcome> filtered = outcomes.stream()
                .filter(o -> finalHorizon.equals(o.getHorizon()))
                .toList();

        // Group by type
        Map<String, List<AlertOutcome>> byType = filtered.stream()
                .collect(Collectors.groupingBy(o -> o.getType() != null ? o.getType() : "unknown"));

        List<DetectorPerformance> detectors = new ArrayList<>();
        for (String t : KNOWN_TYPES) {
            List<AlertOutcome> group = byType.getOrDefault(t, List.of());
            if (type != null && !t.equals(type)) continue;
            detectors.add(aggregate(t, group));
        }
        // Include any types from query not in KNOWN_TYPES
        byType.keySet().stream()
                .filter(t -> !KNOWN_TYPES.contains(t))
                .filter(t -> type == null || t.equals(type))
                .sorted()
                .forEach(t -> detectors.add(aggregate(t, byType.get(t))));

        return new PerformanceResponse(finalHorizon, since.toString(), detectors);
    }

    // ── Aggregation ───────────────────────────────────────────────────────────

    private DetectorPerformance aggregate(String type, List<AlertOutcome> outcomes) {
        int count = outcomes.size();

        long correctCount = outcomes.stream()
                .filter(o -> Boolean.TRUE.equals(o.getWasCorrect()))
                .count();
        long wrongCount = outcomes.stream()
                .filter(o -> Boolean.FALSE.equals(o.getWasCorrect()))
                .count();

        Double precision = (correctCount + wrongCount) == 0
                ? null
                : (double) correctCount / (correctCount + wrongCount);

        double avgMagnitudePp = outcomes.stream()
                .filter(o -> o.getMagnitudePp() != null)
                .mapToDouble(o -> o.getMagnitudePp().doubleValue())
                .average()
                .orElse(0.0);

        List<Double> sorted = outcomes.stream()
                .filter(o -> o.getMagnitudePp() != null)
                .map(o -> o.getMagnitudePp().doubleValue())
                .sorted()
                .toList();
        double medianMagnitudePp = median(sorted);

        double meanAbsMagnitudePp = outcomes.stream()
                .filter(o -> o.getMagnitudePp() != null)
                .mapToDouble(o -> Math.abs(o.getMagnitudePp().doubleValue()))
                .average()
                .orElse(0.0);

        return new DetectorPerformance(type, count, precision,
                avgMagnitudePp, medianMagnitudePp, meanAbsMagnitudePp);
    }

    private static double median(List<Double> sorted) {
        int n = sorted.size();
        if (n == 0) return 0.0;
        if (n % 2 == 0) {
            return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        } else {
            return sorted.get(n / 2);
        }
    }

    // ── GSI query ─────────────────────────────────────────────────────────────

    private List<AlertOutcome> fetchOutcomes(String type, Instant since) {
        DynamoDbIndex<AlertOutcome> gsi = alertOutcomesTable.index("type-firedAt-index");

        if (type != null) {
            return queryForType(gsi, type, since);
        }

        List<AlertOutcome> all = new ArrayList<>();
        for (String t : KNOWN_TYPES) {
            all.addAll(queryForType(gsi, t, since));
        }
        return all;
    }

    private List<AlertOutcome> queryForType(DynamoDbIndex<AlertOutcome> gsi,
                                             String type, Instant since) {
        QueryConditional qc = QueryConditional.sortGreaterThanOrEqualTo(
                Key.builder()
                        .partitionValue(type)
                        .sortValue(since.toString())
                        .build());

        List<AlertOutcome> result = new ArrayList<>();
        try {
            gsi.query(r -> r.queryConditional(qc))
                    .stream()
                    .flatMap(page -> page.items().stream())
                    .forEach(result::add);
        } catch (Exception e) {
            log.warn("signal_performance_query_failed type={} error={}", type, e.getMessage());
        }
        return result;
    }

    // ── Response records ──────────────────────────────────────────────────────

    public record PerformanceResponse(String horizon, String since,
                                      List<DetectorPerformance> detectors) {}

    public record DetectorPerformance(String type, int count, Double precision,
                                      double avgMagnitudePp, double medianMagnitudePp,
                                      double meanAbsMagnitudePp) {}
}
