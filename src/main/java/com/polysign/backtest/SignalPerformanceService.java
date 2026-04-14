package com.polysign.backtest;

import com.polysign.common.AppClock;
import com.polysign.model.AlertOutcome;
import com.polysign.model.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.format.DateTimeParseException;
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
import java.util.OptionalDouble;
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
            "price_movement", "statistical_anomaly", "wallet_activity", "insider_signature");

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

        // Aggregate scorable/unscorable counts across all types for this horizon
        int scorableCount   = (int) filtered.stream().filter(SignalPerformanceService::isScorable).count();
        int unscorableCount = (int) filtered.stream().filter(o -> Boolean.FALSE.equals(o.getScorable())).count();

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

        return new PerformanceResponse(finalHorizon, since.toString(), detectors,
                scorableCount, unscorableCount);
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

        // scan markets table for total/resolved/stuck counts per category
        Map<String, Long> totalMarketsByCategory   = new HashMap<>();
        Map<String, Long> resolvedMarketsByCategory = new HashMap<>();
        Map<String, Long> stuckMarketsByCategory    = new HashMap<>();
        Instant now = clock.now();
        if (marketsTable != null) {
            try {
                marketsTable.scan().items().forEach(m -> {
                    String cat = m.getCategory() != null ? m.getCategory() : "unknown";
                    totalMarketsByCategory.merge(cat, 1L, Long::sum);
                    if (m.getResolvedOutcomePrice() != null) {
                        resolvedMarketsByCategory.merge(cat, 1L, Long::sum);
                    } else if (m.getEndDate() != null) {
                        // stuck = past endDate but resolvedOutcomePrice still null
                        try {
                            if (Instant.parse(m.getEndDate()).isBefore(now)) {
                                stuckMarketsByCategory.merge(cat, 1L, Long::sum);
                            }
                        } catch (DateTimeParseException ignored) {}
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
                            stuckMarketsByCategory.getOrDefault(cat, 0L),
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

    /**
     * Aggregate precision and scored sample count across all detector types for one horizon.
     * Precision denominator now filters to scorable outcomes only.
     *
     * @param horizon one of t15m, t1h, t24h, resolution
     * @param since   look-back window start (null = 7 days ago)
     * @return precision (null if no scored samples) and scoredSamples count
     */
    public AggregatePrecision getAggregatePrecision(String horizon, Instant since) {
        if (since == null) since = clock.now().minus(Duration.ofDays(7));

        List<AlertOutcome> outcomes = fetchOutcomes(null, since);

        long correct = outcomes.stream()
                .filter(o -> horizon.equals(o.getHorizon())
                        && isScorable(o)
                        && Boolean.TRUE.equals(o.getWasCorrect()))
                .count();
        long wrong = outcomes.stream()
                .filter(o -> horizon.equals(o.getHorizon())
                        && isScorable(o)
                        && Boolean.FALSE.equals(o.getWasCorrect()))
                .count();
        long scored = correct + wrong;
        Double precision = scored == 0 ? null : (double) correct / scored;

        return new AggregatePrecision(precision, scored);
    }

    /**
     * Aggregate Brier skill across all detector types for one horizon.
     *
     * @param horizon one of t15m, t1h, t24h, resolution
     * @param since   look-back window start (null = 7 days ago)
     * @return aggregate Brier skill statistics
     */
    public AggregateSkill getAggregateSkill(String horizon, Instant since) {
        if (since == null) since = clock.now().minus(Duration.ofDays(7));

        List<AlertOutcome> outcomes = fetchOutcomes(null, since);

        List<AlertOutcome> forHorizon = outcomes.stream()
                .filter(o -> horizon.equals(o.getHorizon()))
                .toList();

        int scorableCount   = (int) forHorizon.stream().filter(SignalPerformanceService::isScorable).count();
        int unscorableCount = (int) forHorizon.stream().filter(o -> Boolean.FALSE.equals(o.getScorable())).count();

        // overall: all scorable with non-null brierSkill
        OptionalDouble overall = forHorizon.stream()
                .filter(o -> isScorable(o) && o.getBrierSkill() != null)
                .mapToDouble(o -> o.getBrierSkill().doubleValue())
                .average();
        long overallCount = forHorizon.stream()
                .filter(o -> isScorable(o) && o.getBrierSkill() != null)
                .count();

        // coreZone: scorable + not dead zone
        OptionalDouble coreZone = forHorizon.stream()
                .filter(o -> isScorable(o) && !Boolean.TRUE.equals(o.getDeadZone())
                        && o.getBrierSkill() != null)
                .mapToDouble(o -> o.getBrierSkill().doubleValue())
                .average();
        long coreZoneCount = forHorizon.stream()
                .filter(o -> isScorable(o) && !Boolean.TRUE.equals(o.getDeadZone())
                        && o.getBrierSkill() != null)
                .count();

        return new AggregateSkill(
                overall.isPresent()   ? overall.getAsDouble()   : null, (int) overallCount,
                coreZone.isPresent()  ? coreZone.getAsDouble()  : null, (int) coreZoneCount,
                scorableCount, unscorableCount);
    }

    // ── Aggregation ───────────────────────────────────────────────────────────

    private DetectorPerformance aggregate(String type, List<AlertOutcome> outcomes) {
        int count = outcomes.size();

        // Scorable/unscorable split (null scorable = old row → treat as scorable)
        List<AlertOutcome> scorable   = outcomes.stream().filter(SignalPerformanceService::isScorable).toList();
        int scorableCount   = scorable.size();
        int unscorableCount = (int) outcomes.stream().filter(o -> Boolean.FALSE.equals(o.getScorable())).count();

        // Precision uses scorable-only denominator
        long correctCount = scorable.stream().filter(o -> Boolean.TRUE.equals(o.getWasCorrect())).count();
        long wrongCount   = scorable.stream().filter(o -> Boolean.FALSE.equals(o.getWasCorrect())).count();
        Double precision  = (correctCount + wrongCount) == 0
                ? null : (double) correctCount / (correctCount + wrongCount);

        double avgMagnitudePp = outcomes.stream()
                .filter(o -> o.getMagnitudePp() != null)
                .mapToDouble(o -> o.getMagnitudePp().doubleValue())
                .average()
                .orElse(0.0);

        List<Double> sortedMag = outcomes.stream()
                .filter(o -> o.getMagnitudePp() != null)
                .map(o -> o.getMagnitudePp().doubleValue())
                .sorted()
                .toList();
        double medianMagnitudePp    = median(sortedMag);
        double meanAbsMagnitudePp   = outcomes.stream()
                .filter(o -> o.getMagnitudePp() != null)
                .mapToDouble(o -> Math.abs(o.getMagnitudePp().doubleValue()))
                .average()
                .orElse(0.0);

        // Skill buckets
        SkillBucket overall  = buildSkillBucket(scorable, false);
        SkillBucket coreZone = buildSkillBucket(scorable, true);

        return new DetectorPerformance(type, count, precision,
                avgMagnitudePp, medianMagnitudePp, meanAbsMagnitudePp,
                scorableCount, unscorableCount, overall, coreZone);
    }

    /** Build a {@link SkillBucket} for the given scorable outcomes.
     *  @param excludeDeadZone when true, outcomes with deadZone=true are excluded */
    private static SkillBucket buildSkillBucket(List<AlertOutcome> scorable, boolean excludeDeadZone) {
        List<AlertOutcome> bucket = excludeDeadZone
                ? scorable.stream().filter(o -> !Boolean.TRUE.equals(o.getDeadZone())).toList()
                : scorable;

        long correct  = bucket.stream().filter(o -> Boolean.TRUE.equals(o.getWasCorrect())).count();
        long wrong    = bucket.stream().filter(o -> Boolean.FALSE.equals(o.getWasCorrect())).count();
        int  scored   = (int) (correct + wrong);
        Double prec   = scored == 0 ? null : (double) correct / scored;

        List<AlertOutcome> withBrier = bucket.stream()
                .filter(o -> o.getBrierSkill() != null)
                .toList();
        int brierCount = withBrier.size();

        OptionalDouble meanSkill  = withBrier.stream().mapToDouble(o -> o.getBrierSkill().doubleValue()).average();
        OptionalDouble meanMkt    = withBrier.stream()
                .filter(o -> o.getMarketBrier() != null)
                .mapToDouble(o -> o.getMarketBrier().doubleValue()).average();
        OptionalDouble meanDet    = withBrier.stream()
                .filter(o -> o.getDetectorBrier() != null)
                .mapToDouble(o -> o.getDetectorBrier().doubleValue()).average();

        return new SkillBucket(
                prec, (int) correct, scored,
                meanSkill.isPresent() ? meanSkill.getAsDouble() : null,
                meanMkt.isPresent()   ? meanMkt.getAsDouble()   : null,
                meanDet.isPresent()   ? meanDet.getAsDouble()   : null,
                brierCount);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns true when the outcome is scorable.
     *  null scorable = old row before skill-overhaul — treat as scorable (backward compat). */
    static boolean isScorable(AlertOutcome o) {
        return !Boolean.FALSE.equals(o.getScorable());
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

    public record PerformanceResponse(
            String horizon,
            String since,
            List<DetectorPerformance> detectors,
            /** Total scorable outcomes at this horizon (scorable != false). */
            int scorableCount,
            /** Total unscorable outcomes at this horizon (no baseline price). */
            int unscorableCount) {}

    public record DetectorPerformance(
            String type,
            int count,
            /** Precision computed over scorable outcomes only (wasCorrect true/false, no flat). */
            Double precision,
            double avgMagnitudePp,
            double medianMagnitudePp,
            double meanAbsMagnitudePp,
            /** Outcomes with a valid priceAtAlert baseline. */
            int scorableCount,
            /** Outcomes lacking a baseline (pre-deploy data). */
            int unscorableCount,
            /** Skill metrics including dead-zone alerts. */
            SkillBucket overall,
            /** Skill metrics excluding dead-zone alerts (priceAtAlert 0–10% or 90–100%). */
            SkillBucket coreZone) {}

    /** Precision and Brier-skill aggregates for one alert bucket (overall or core-zone). */
    public record SkillBucket(
            Double precision,
            int correctCount,
            int scoredCount,
            /** Mean Brier skill: positive = detector beat market's implied probability. */
            Double meanBrierSkill,
            Double meanMarketBrier,
            Double meanDetectorBrier,
            int brierCount) {}

    public record CategoryPerformanceResponse(String since, List<CategoryPerformance> categories) {}

    public record CategoryPerformance(
            String category, int alertCount, int sampleCount,
            PrecisionStat t15m, PrecisionStat t1h, PrecisionStat t24h,
            double avgMagnitudePp) {}

    /** Numerator, denominator and derived precision for a single horizon bucket. */
    public record PrecisionStat(Double precision, int numerator, int denominator) {}

    /** Aggregate precision and scored sample count across all detector types for one horizon. */
    public record AggregatePrecision(Double precision, long scoredSamples) {}

    /** Aggregate Brier skill across all detector types for one horizon. */
    public record AggregateSkill(
            Double meanBrierSkillOverall,
            int brierCountOverall,
            Double meanBrierSkillCoreZone,
            int brierCountCoreZone,
            int scorableCount,
            int unscorableCount) {}

    public record ResolutionCoverageResponse(String since, List<CategoryCoverage> categories) {}

    public record CategoryCoverage(
            String category,
            long totalMarkets,
            long resolvedMarkets,
            long stuckMarkets,
            long marketsWithSignal,
            long signalsTotal,
            long signalsResolved) {}
}
