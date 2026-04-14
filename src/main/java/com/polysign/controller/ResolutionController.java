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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Serves resolved alert outcomes for the dashboard's "Recent Resolutions" panel.
 *
 * GET /api/resolutions/recent?limit=20
 *
 * Scans alert_outcomes for horizon="resolution" rows, sorts by evaluatedAt
 * descending, and enriches each with:
 *   - title / link / priceAtAlert  from the alerts table (query by alertId)
 *   - marketQuestion               from the markets table (getItem by marketId)
 *
 * If either join misses (alert TTL'd, market not found), the field is null and
 * the dashboard renders "—".
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

    public record ResolutionItemDto(
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
            String     marketQuestion) {}

    @GetMapping("/recent")
    public List<ResolutionItemDto> getRecentResolutions(
            @RequestParam(defaultValue = "20") int limit) {

        List<AlertOutcome> outcomes = alertOutcomesTable.scan().items().stream()
                .filter(o -> "resolution".equals(o.getHorizon()))
                .sorted(Comparator.comparing(AlertOutcome::getEvaluatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .toList();

        // ── Batch-load markets for unique marketIds (one getItem each, bounded by limit) ──
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

        return outcomes.stream()
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
                    // Intentionally no fallback to o.getPriceAtAlert():
                    // that field carries the resolution-tick price for pre-deploy rows,
                    // which is wrong. Null is the correct display for pre-deploy alerts
                    // until they roll off (~7 days).

                    return new ResolutionItemDto(
                            o.getAlertId(),
                            o.getMarketId(),
                            o.getFiredAt(),
                            o.getEvaluatedAt(),
                            o.getDirectionPredicted(),
                            o.getDirectionRealized(),
                            priceAtAlert,
                            o.getPriceAtHorizon(),
                            o.getType(),
                            title,
                            link,
                            marketQuestions.get(o.getMarketId()));
                })
                .toList();
    }
}
