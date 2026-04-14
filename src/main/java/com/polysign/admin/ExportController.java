package com.polysign.admin;

import com.polysign.model.Alert;
import com.polysign.model.AlertOutcome;
import com.polysign.model.LiquidityTier;
import com.polysign.model.Market;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * One-shot admin endpoint that dumps every resolved alert + its outcome to CSV.
 *
 * <p>Auth: {@code X-Admin-Key} header must match the {@code ADMIN_EXPORT_KEY} env var.
 *
 * <p>Usage: {@code curl -H "X-Admin-Key: $ADMIN_EXPORT_KEY" .../admin/export/resolutions.csv}
 */
@RestController
@RequestMapping("/admin/export")
public class ExportController {

    private static final Logger log = LoggerFactory.getLogger(ExportController.class);

    static final String HEADER_ROW =
            "alert_id,market_id,market_title,detector_type,severity," +
            "alert_created_at,price_at_alert,predicted_direction," +
            "resolution_price,resolved_at,realized_direction," +
            "outcome_correct,minutes_before_resolution," +
            "market_volume_24h,market_tier";

    private final DynamoDbTable<AlertOutcome> alertOutcomesTable;
    private final DynamoDbTable<Alert>        alertsTable;
    private final DynamoDbTable<Market>       marketsTable;
    private final String                      adminExportKey;
    private final double                      tier1MinVolume;
    private final double                      tier2MinVolume;

    public ExportController(
            DynamoDbTable<AlertOutcome> alertOutcomesTable,
            DynamoDbTable<Alert>        alertsTable,
            DynamoDbTable<Market>       marketsTable,
            @Value("${ADMIN_EXPORT_KEY:}") String adminExportKey,
            @Value("${polysign.detectors.liquidity-tiers.tier1-min-volume:250000}") double tier1MinVolume,
            @Value("${polysign.detectors.liquidity-tiers.tier2-min-volume:50000}")  double tier2MinVolume) {
        this.alertOutcomesTable = alertOutcomesTable;
        this.alertsTable        = alertsTable;
        this.marketsTable       = marketsTable;
        this.adminExportKey     = adminExportKey;
        this.tier1MinVolume     = tier1MinVolume;
        this.tier2MinVolume     = tier2MinVolume;
    }

    @GetMapping(value = "/resolutions.csv", produces = "text/csv")
    public void exportResolutions(
            @RequestHeader(value = "X-Admin-Key", required = false) String providedKey,
            HttpServletResponse response) throws IOException {

        if (adminExportKey.isBlank() || !adminExportKey.equals(providedKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid or missing admin key\"}");
            return;
        }

        // 1. Scan alert_outcomes for horizon=resolution (paginated via enhanced client iteration)
        List<AlertOutcome> outcomes = new ArrayList<>();
        alertOutcomesTable.scan().items().stream()
                .filter(o -> "resolution".equals(o.getHorizon()))
                .forEach(outcomes::add);

        // 2. Load alerts (join by alertId — query by PK only, same as ResolutionController)
        Set<String> alertIds = new HashSet<>();
        for (AlertOutcome o : outcomes) {
            if (o.getAlertId() != null && !o.getAlertId().isBlank()) alertIds.add(o.getAlertId());
        }
        Map<String, Alert> alertsById = new HashMap<>();
        for (String alertId : alertIds) {
            try {
                alertsTable.query(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(alertId).build()))
                        .items().stream()
                        .findFirst()
                        .ifPresent(a -> alertsById.put(alertId, a));
            } catch (Exception e) {
                log.debug("export_alert_load_failed alertId={} error={}", alertId, e.getMessage());
            }
        }

        // 3. Load markets (join by marketId)
        Set<String> marketIds = new HashSet<>();
        for (AlertOutcome o : outcomes) {
            if (o.getMarketId() != null && !o.getMarketId().isBlank()) marketIds.add(o.getMarketId());
        }
        Map<String, Market> marketsById = new HashMap<>();
        for (String marketId : marketIds) {
            try {
                Market m = marketsTable.getItem(Key.builder().partitionValue(marketId).build());
                if (m != null) marketsById.put(marketId, m);
            } catch (Exception e) {
                log.debug("export_market_load_failed marketId={} error={}", marketId, e.getMessage());
            }
        }

        // 4. Sort: resolved_at DESC, then alert_created_at ASC (within market)
        outcomes.sort(Comparator
                .comparing(AlertOutcome::getEvaluatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(
                        o -> {
                            Alert a = alertsById.get(o.getAlertId());
                            return a != null ? a.getCreatedAt() : "";
                        },
                        Comparator.nullsLast(Comparator.naturalOrder())));

        // 5. Stream CSV to response
        response.setContentType("text/csv");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"resolutions.csv\"");

        PrintWriter writer = response.getWriter();
        writer.println(HEADER_ROW);

        for (AlertOutcome o : outcomes) {
            Alert  alert  = alertsById.get(o.getAlertId());
            Market market = marketsById.get(o.getMarketId());

            String     marketTitle    = market != null ? market.getQuestion()    : null;
            String     severity       = alert  != null ? alert.getSeverity()     : null;
            String     alertCreatedAt = alert  != null ? alert.getCreatedAt()    : null;
            BigDecimal priceAtAlert   = alert  != null ? alert.getPriceAtAlert() : null;
            BigDecimal resolutionPrice = o.getPriceAtHorizon();
            String     resolvedAt     = o.getEvaluatedAt();
            String     volume24h      = market != null ? market.getVolume24h()   : null;

            String predictedDirection = upperOrEmpty(o.getDirectionPredicted());
            String realizedDirection  = computeRealizedDirection(resolutionPrice, priceAtAlert);
            String outcomeCorrect     = o.getWasCorrect() == null ? "" : o.getWasCorrect().toString();
            String minutesBefore      = computeMinutes(resolvedAt, alertCreatedAt);
            String marketTier         = market != null
                    ? LiquidityTier.classifyRaw(volume24h, tier1MinVolume, tier2MinVolume).label()
                    : "";

            writer.println(csvRow(
                    o.getAlertId(),
                    o.getMarketId(),
                    marketTitle,
                    o.getType(),
                    severity,
                    alertCreatedAt,
                    priceAtAlert   != null ? priceAtAlert.toPlainString()    : "",
                    predictedDirection,
                    resolutionPrice != null ? resolutionPrice.toPlainString() : "",
                    resolvedAt,
                    realizedDirection,
                    outcomeCorrect,
                    minutesBefore,
                    volume24h != null ? volume24h : "",
                    marketTier));
        }

        writer.flush();
        log.info("export_resolutions_complete rows={}", outcomes.size());
    }

    // ── Helpers (package-private for unit testing) ────────────────────────────

    private static String upperOrEmpty(String s) {
        if (s == null) return "";
        return s.toUpperCase(Locale.ROOT);
    }

    private static String computeRealizedDirection(BigDecimal resolutionPrice, BigDecimal priceAtAlert) {
        if (resolutionPrice == null || priceAtAlert == null) return "";
        int cmp = resolutionPrice.compareTo(priceAtAlert);
        if (cmp > 0) return "UP";
        if (cmp < 0) return "DOWN";
        return "FLAT";
    }

    private static String computeMinutes(String resolvedAt, String alertCreatedAt) {
        if (resolvedAt == null || alertCreatedAt == null) return "";
        try {
            long minutes = Duration.between(
                    Instant.parse(alertCreatedAt), Instant.parse(resolvedAt)).toMinutes();
            return Long.toString(minutes);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Builds a single CSV row. Each field is escaped per RFC 4180:
     * values containing commas, double-quotes, or newlines are wrapped in
     * double-quotes, and embedded double-quotes are doubled.
     */
    static String csvRow(String... fields) {
        StringJoiner joiner = new StringJoiner(",");
        for (String field : fields) {
            joiner.add(csvEscape(field));
        }
        return joiner.toString();
    }

    static String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
