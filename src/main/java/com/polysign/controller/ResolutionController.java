package com.polysign.controller;

import com.polysign.model.Alert;
import com.polysign.model.AlertOutcome;
import com.polysign.model.Market;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.math.BigDecimal;
import java.util.*;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

/**
 * Serves resolved alert outcomes for the dashboard's "Recent Resolutions" panel.
 *
 * GET /api/resolutions/recent?limit=20
 *
 * Returns one {@link ResolvedMarketCard} per distinct marketId. Each card contains
 * all alerts that fired on that market, sorted by firedAt ASC. Cards are sorted by
 * max(evaluatedAt) DESC so the most recently resolved market appears first.
 *
 * The {@link #groupByMarket} method is package-private so it can be unit-tested
 * independently of the DynamoDB layer.
 */
@RestController
@RequestMapping("/api/resolutions")
public class ResolutionController {

    private final DynamoDbTable<AlertOutcome> alertOutcomesTable;
    private final DynamoDbTable<Alert>        alertsTable;
    private final DynamoDbTable<Market>       marketsTable;

    public ResolutionController(
            DynamoDbTable<AlertOutcome> alertOutcomesTable,
            DynamoDbTable<Alert>        alertsTable,
            DynamoDbTable<Market>       marketsTable) {
        this.alertOutcomesTable = alertOutcomesTable;
        this.alertsTable        = alertsTable;
        this.marketsTable       = marketsTable;
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /**
     * Enriched per-alert data after joining with the alerts and markets tables.
     * This is the input type for {@link #groupByMarket}.
     */
    public record ResolvedOutcome(
            String     alertId,
            String     marketId,
            String     firedAt,
            String     evaluatedAt,
            String     directionPredicted,
            String     directionRealized,
            BigDecimal priceAtAlert,
            BigDecimal priceAtHorizon,
            String     type,
            String     title,
            String     link,
            String     marketQuestion,
            boolean    alertCorrect,
            BigDecimal brierSkill,
            Boolean    deadZone,
            Boolean    scorable) {}

    /** One alert row rendered inside a market card. */
    public record AlertRow(
            String     alertId,
            String     type,
            BigDecimal priceAtAlert,
            BigDecimal priceAtHorizon,
            String     firedAt,
            String     evaluatedAt,
            String     directionPredicted,
            String     directionRealized,
            boolean    alertCorrect,
            BigDecimal brierSkill,
            Boolean    deadZone,
            Boolean    scorable) {}

    /** One card per resolved market, containing all alerts that fired on it. */
    public record ResolvedMarketCard(
            String         marketId,
            String         marketTitle,
            String         link,
            BigDecimal     resolutionPrice,
            String         resolvedAt,
            /** true if ANY alert is directionally correct (legacy field, kept for compatibility). */
            boolean        marketCorrect,
            /** true if mean Brier skill across this market's scorable alerts is positive. */
            boolean        marketInformative,
            /** Mean Brier skill across scorable alerts for this market; null if no Brier data. */
            Double         meanBrierSkill,
            List<AlertRow> alerts) {}

    // ── Grouping ──────────────────────────────────────────────────────────────

    /**
     * Groups a flat list of enriched per-alert outcomes into one card per market.
     *
     * <ul>
     *   <li>Cards are sorted by max(evaluatedAt) DESC.</li>
     *   <li>Alerts within each card are sorted by firedAt ASC.</li>
     *   <li>{@code marketCorrect} is true if ANY alert in the group is correct.</li>
     * </ul>
     */
    static List<ResolvedMarketCard> groupByMarket(List<ResolvedOutcome> outcomes) {
        // LinkedHashMap preserves insertion order while we build groups
        Map<String, List<ResolvedOutcome>> byMarket = new LinkedHashMap<>();
        for (ResolvedOutcome o : outcomes) {
            String key = o.marketId() != null ? o.marketId() : "";
            byMarket.computeIfAbsent(key, k -> new ArrayList<>()).add(o);
        }

        return byMarket.values().stream()
                .map(group -> {
                    List<AlertRow> alertRows = group.stream()
                            .sorted(Comparator.comparing(ResolvedOutcome::firedAt,
                                    Comparator.nullsLast(Comparator.naturalOrder())))
                            .map(o -> new AlertRow(
                                    o.alertId(),
                                    o.type(),
                                    o.priceAtAlert(),
                                    o.priceAtHorizon(),
                                    o.firedAt(),
                                    o.evaluatedAt(),
                                    o.directionPredicted(),
                                    o.directionRealized(),
                                    o.alertCorrect(),
                                    o.brierSkill(),
                                    o.deadZone(),
                                    o.scorable()))
                            .toList();

                    ResolvedOutcome representative = group.stream()
                            .max(Comparator.comparing(ResolvedOutcome::evaluatedAt,
                                    Comparator.nullsLast(Comparator.naturalOrder())))
                            .orElseThrow();

                    boolean marketCorrect = group.stream().anyMatch(ResolvedOutcome::alertCorrect);

                    // marketInformative: mean brierSkill > 0 across scorable alerts with Brier data
                    OptionalDouble meanSkillOpt = group.stream()
                            .filter(o -> !Boolean.FALSE.equals(o.scorable()) && o.brierSkill() != null)
                            .mapToDouble(o -> o.brierSkill().doubleValue())
                            .average();
                    boolean marketInformative = meanSkillOpt.isPresent() && meanSkillOpt.getAsDouble() > 0;
                    Double meanBrierSkill = meanSkillOpt.isPresent() ? meanSkillOpt.getAsDouble() : null;

                    String marketTitle = representative.marketQuestion() != null
                            ? representative.marketQuestion()
                            : representative.title();

                    return new ResolvedMarketCard(
                            representative.marketId(),
                            marketTitle,
                            representative.link(),
                            representative.priceAtHorizon(),
                            representative.evaluatedAt(),
                            marketCorrect,
                            marketInformative,
                            meanBrierSkill,
                            alertRows);
                })
                .sorted(Comparator.comparing(
                        card -> card.resolvedAt() == null ? "" : card.resolvedAt(),
                        Comparator.reverseOrder()))
                .toList();
    }

    // ── Endpoint ──────────────────────────────────────────────────────────────

    @GetMapping("/recent")
    public List<ResolvedMarketCard> getRecentResolutions(
            @RequestParam(defaultValue = "20") int limit) {

        List<AlertOutcome> outcomes = alertOutcomesTable.scan().items().stream()
                .filter(o -> "resolution".equals(o.getHorizon()))
                .sorted(Comparator.comparing(AlertOutcome::getEvaluatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .toList();

        // Batch-load markets for unique marketIds
        Set<String> marketIds = outcomes.stream()
                .map(AlertOutcome::getMarketId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        Map<String, String> marketQuestions = new HashMap<>();
        for (String marketId : marketIds) {
            try {
                Market m = marketsTable.getItem(Key.builder().partitionValue(marketId).build());
                if (m != null && m.getQuestion() != null) {
                    String q = m.getQuestion();
                    marketQuestions.put(marketId, q.length() > 80 ? q.substring(0, 80) + "…" : q);
                }
            } catch (Exception ignored) {}
        }

        // Enrich each outcome with alert-row data
        List<ResolvedOutcome> resolved = outcomes.stream()
                .map(o -> {
                    String title = null, link = null;
                    BigDecimal priceAtAlert = null;
                    try {
                        if (o.getAlertId() != null) {
                            // Query by partition key only — avoids SK mismatch between
                            // firedAt (detectedAt timestamp) and createdAt (bucketed SK).
                            Alert alert = alertsTable
                                    .query(QueryConditional.keyEqualTo(
                                            Key.builder().partitionValue(o.getAlertId()).build()))
                                    .items()
                                    .stream()
                                    .findFirst()
                                    .orElse(null);
                            if (alert != null) {
                                title        = alert.getTitle();
                                link         = alert.getLink();
                                priceAtAlert = alert.getPriceAtAlert();
                            }
                        }
                    } catch (Exception ignored) {}
                    // No fallback to o.getPriceAtAlert(): pre-deploy rows carry the
                    // resolution-tick price there, which is wrong. Null is correct.

                    String dp = o.getDirectionPredicted();
                    String dr = o.getDirectionRealized();
                    boolean alertCorrect = dp != null && dr != null && dp.equals(dr);

                    return new ResolvedOutcome(
                            o.getAlertId(),
                            o.getMarketId(),
                            o.getFiredAt(),
                            o.getEvaluatedAt(),
                            dp,
                            dr,
                            priceAtAlert,
                            o.getPriceAtHorizon(),
                            o.getType(),
                            title,
                            link,
                            marketQuestions.get(o.getMarketId()),
                            alertCorrect,
                            o.getBrierSkill(),
                            o.getDeadZone(),
                            o.getScorable());
                })
                .toList();

        return groupByMarket(resolved);
    }
}
