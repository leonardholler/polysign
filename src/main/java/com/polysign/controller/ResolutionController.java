package com.polysign.controller;

import com.polysign.model.Alert;
import com.polysign.model.AlertOutcome;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Serves resolved alert outcomes for the dashboard's "Recent Resolutions" panel.
 *
 * GET /api/resolutions/recent?limit=20
 *
 * Scans alert_outcomes for horizon="resolution" rows, sorts by evaluatedAt
 * descending, and enriches each with title/link from the alerts table.
 * If the alerts join misses (alert TTL'd or not found), title/link are omitted.
 */
@RestController
@RequestMapping("/api/resolutions")
public class ResolutionController {

    private final DynamoDbTable<AlertOutcome> alertOutcomesTable;
    private final DynamoDbTable<Alert>        alertsTable;

    public ResolutionController(
            DynamoDbTable<AlertOutcome> alertOutcomesTable,
            DynamoDbTable<Alert>        alertsTable) {
        this.alertOutcomesTable = alertOutcomesTable;
        this.alertsTable        = alertsTable;
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
            String     link) {}

    @GetMapping("/recent")
    public List<ResolutionItemDto> getRecentResolutions(
            @RequestParam(defaultValue = "20") int limit) {

        List<AlertOutcome> outcomes = alertOutcomesTable.scan().items().stream()
                .filter(o -> "resolution".equals(o.getHorizon()))
                .sorted(Comparator.comparing(AlertOutcome::getEvaluatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .toList();

        return outcomes.stream()
                .map(o -> {
                    String title = null, link = null;
                    try {
                        if (o.getAlertId() != null && o.getFiredAt() != null) {
                            Alert alert = alertsTable.getItem(
                                    Key.builder()
                                       .partitionValue(o.getAlertId())
                                       .sortValue(o.getFiredAt())
                                       .build());
                            if (alert != null) {
                                title = alert.getTitle();
                                link  = alert.getLink();
                            }
                        }
                    } catch (Exception ignored) {}

                    return new ResolutionItemDto(
                            o.getAlertId(),
                            o.getMarketId(),
                            o.getFiredAt(),
                            o.getEvaluatedAt(),
                            o.getDirectionPredicted(),
                            o.getDirectionRealized(),
                            o.getPriceAtAlert(),
                            o.getPriceAtHorizon(),
                            o.getType(),
                            title,
                            link);
                })
                .toList();
    }
}
