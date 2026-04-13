package com.polysign.backtest;

import com.polysign.common.AppClock;
import com.polysign.model.AlertOutcome;
import com.polysign.model.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.ReadBatch;

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

    public static final List<String> KNOWN_TYPES = List.of(
            "price_movement", "statistical_anomaly", "wallet_activity");

    private final DynamoDbTable<AlertOutcome> alertOutcomesTable;
    private final DynamoDbTable<Market>       marketsTable;
    private final DynamoDbEnhancedClient      enhancedClient;
    private final AppClock clock;

    @Autowired
    public SignalPerformanceService(DynamoDbTable<AlertOutcome> alertOutcomesTable,
                                    DynamoDbTable<Market> marketsTable,
                                    DynamoDbEnhancedClient enhancedClient,
                                    AppClock clock) {
        this.alertOutcomesTable = alertOutcomesTable;
        this.marketsTable       = marketsTable;
        this.enhancedClient     = enhancedClient;
        this.clock              = clock;
    }

    // Test constructor — marketsTable/enhancedClient null; subclass overrides getPerformance().
    SignalPerformanceService(DynamoDbTable<AlertOutcome> alertOutcomesTable, AppClock clock) {
        this.alertOutcomesTable = alertOutcomesTable;
        this.marketsTable       = null;
        this.enhancedClient     = null;
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

    /**
     * Compute per-Polymarket-category signal performance.
     *
     * <p>Fetches all outcomes via the existing type-firedAt-index GSI (all 5 known types).
     * Outcomes written before Phase 13 have a null {@code category} field; these are
     * batch-resolved from the markets table by marketId. Outcomes whose market no longer
     * exists in the table are grouped under "unknown".
     *
     * @param since look back from this instant (default 7 days ago)
     * @return response record ready for JSON serialisation
     */
    public CategoryPerformanceResponse byCategoryPerformance(Instant since) {
        if (since == null) since = clock.now().minus(Duration.ofDays(7));

        List<AlertOutcome> outcomes = fetchOutcomes(null, since);

        // Collect marketIds that need category resolution (outcome.category is null)
        Set<String> needsResolve = outcomes.stream()
                .filter(o -> o.getCategory() == null && o.getMarketId() != null)
                .map(AlertOutcome::getMarketId)
                .collect(Collectors.toSet());

        Map<String, String> categoryByMarketId = resolveMarketCategories(needsResolve);

        // Group outcomes by category
        Map<String, List<AlertOutcome>> byCategory = new LinkedHashMap<>();
        for (AlertOutcome o : outcomes) {
            String cat = o.getCategory();
            if (cat == null) cat = categoryByMarketId.getOrDefault(o.getMarketId(), "unknown");
            byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(o);
        }

        List<CategoryPerformance> categories = byCategory.entrySet().stream()
                .map(e -> aggregateByCategory(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(c -> c.category() == null ? "" : c.category()))
                .toList();

        return new CategoryPerformanceResponse(since.toString(), categories);
    }

    private CategoryPerformance aggregateByCategory(String category, List<AlertOutcome> outcomes) {
        // alertCount: distinct alertIds
        long alertCount = outcomes.stream()
                .map(AlertOutcome::getAlertId)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        // sampleCount: total non-flat outcomes across all horizons (used for display)
        long sampleCount = outcomes.stream()
                .filter(o -> o.getWasCorrect() != null)
                .count();

        PrecisionStat t15m = precisionForHorizon(outcomes, "t15m");
        PrecisionStat t1h  = precisionForHorizon(outcomes, "t1h");
        PrecisionStat t24h = precisionForHorizon(outcomes, "t24h");

        double avgMagnitudePp = outcomes.stream()
                .filter(o -> o.getMagnitudePp() != null)
                .mapToDouble(o -> o.getMagnitudePp().doubleValue())
                .average()
                .orElse(0.0);

        return new CategoryPerformance(
                category, (int) alertCount, (int) sampleCount,
                t15m, t1h, t24h, avgMagnitudePp);
    }

    private static PrecisionStat precisionForHorizon(List<AlertOutcome> outcomes, String horizon) {
        long correct = outcomes.stream()
                .filter(o -> horizon.equals(o.getHorizon()) && Boolean.TRUE.equals(o.getWasCorrect()))
                .count();
        long wrong = outcomes.stream()
                .filter(o -> horizon.equals(o.getHorizon()) && Boolean.FALSE.equals(o.getWasCorrect()))
                .count();
        int den = (int) (correct + wrong);
        return new PrecisionStat(den == 0 ? null : (double) correct / den, (int) correct, den);
    }

    /**
     * Diagnostic: per-category resolution coverage for the last 7 days.
     *
     * <p>Returns, for each category observed in outcomes or markets:
     * total markets, resolved markets (resolvedOutcomePrice != null),
     * distinct markets that fired at least one signal, total signals,
     * and signals whose wasCorrect label has been set.
     *
     * @param since look back from this instant (default 7 days ago)
     */
    public ResolutionCoverageResponse getResolutionCoverage(Instant since) {
        if (since == null) since = clock.now().minus(Duration.ofDays(7));

        List<AlertOutcome> outcomes = fetchOutcomes(null, since);

        // resolve categories for pre-Phase-13 outcomes
        Set<String> needsResolve = outcomes.stream()
                .filter(o -> o.getCategory() == null && o.getMarketId() != null)
                .map(AlertOutcome::getMarketId)
                .collect(Collectors.toSet());
        Map<String, String> categoryByMarketId = resolveMarketCategories(needsResolve);

        // group outcomes by resolved category
        Map<String, List<AlertOutcome>> outcomesByCategory = new LinkedHashMap<>();
        for (AlertOutcome o : outcomes) {
            String cat = o.getCategory();
            if (cat == null) cat = categoryByMarketId.getOrDefault(o.getMarketId(), "unknown");
            outcomesByCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(o);
        }

        // scan markets table for total/resolved counts per category
        Map<String, Long> totalMarketsByCategory   = new HashMap<>();
        Map<String, Long> resolvedMarketsByCategory = new HashMap<>();
        if (marketsTable != null) {
            try {
                marketsTable.scan().items().forEach(m -> {
                    String cat = m.getCategory() != null ? m.getCategory() : "unknown";
                    totalMarketsByCategory.merge(cat, 1L, Long::sum);
                    if (m.getResolvedOutcomePrice() != null) {
                        resolvedMarketsByCategory.merge(cat, 1L, Long::sum);
                    }
                });
            } catch (Exception e) {
                log.warn("resolution_coverage_market_scan_failed error={}", e.getMessage());
            }
        }

        Set<String> allCategories = new HashSet<>();
        allCategories.addAll(outcomesByCategory.keySet());
        allCategories.addAll(totalMarketsByCategory.keySet());

        List<CategoryCoverage> categories = allCategories.stream()
                .sorted()
                .map(cat -> {
                    List<AlertOutcome> catOutcomes = outcomesByCategory.getOrDefault(cat, List.of());
                    long marketsWithSignal = catOutcomes.stream()
                            .map(AlertOutcome::getMarketId)
                            .filter(Objects::nonNull)
                            .distinct()
                            .count();
                    long signalsTotal    = catOutcomes.size();
                    long signalsResolved = catOutcomes.stream()
                            .filter(o -> o.getWasCorrect() != null)
                            .count();
                    return new CategoryCoverage(
                            cat,
                            totalMarketsByCategory.getOrDefault(cat, 0L),
                            resolvedMarketsByCategory.getOrDefault(cat, 0L),
                            marketsWithSignal,
                            signalsTotal,
                            signalsResolved);
                })
                .toList();

        return new ResolutionCoverageResponse(since.toString(), categories);
    }

    /**
     * Batch-resolves market categories for a set of marketIds.
     * Uses DynamoDB batchGetItem in chunks of 100. Returns an empty map if
     * marketsTable or enhancedClient is null (test mode).
     */
    private Map<String, String> resolveMarketCategories(Set<String> marketIds) {
        if (marketsTable == null || enhancedClient == null || marketIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        List<String> idList = new ArrayList<>(marketIds);

        // batchGetItem limit is 100 items per request
        for (int i = 0; i < idList.size(); i += 100) {
            List<String> chunk = idList.subList(i, Math.min(i + 100, idList.size()));
            try {
                ReadBatch.Builder<Market> rb = ReadBatch.builder(Market.class)
                        .mappedTableResource(marketsTable);
                for (String id : chunk) {
                    rb.addGetItem(Key.builder().partitionValue(id).build());
                }
                enhancedClient.batchGetItem(r -> r.addReadBatch(rb.build()))
                        .resultsForTable(marketsTable)
                        .forEach(m -> {
                            if (m.getCategory() != null) {
                                result.put(m.getMarketId(), m.getCategory());
                            }
                        });
            } catch (Exception e) {
                log.warn("signal_performance_category_resolve_failed error={}", e.getMessage());
            }
        }
        return result;
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

    public record CategoryPerformanceResponse(String since, List<CategoryPerformance> categories) {}

    public record CategoryPerformance(
            String category, int alertCount, int sampleCount,
            PrecisionStat t15m, PrecisionStat t1h, PrecisionStat t24h,
            double avgMagnitudePp) {}

    /** Numerator, denominator and derived precision for a single horizon bucket. */
    public record PrecisionStat(Double precision, int numerator, int denominator) {}

    public record ResolutionCoverageResponse(String since, List<CategoryCoverage> categories) {}

    public record CategoryCoverage(
            String category,
            long totalMarkets,
            long resolvedMarkets,
            long marketsWithSignal,
            long signalsTotal,
            long signalsResolved) {}
}
