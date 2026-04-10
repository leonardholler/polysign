package com.polysign.backtest;

import com.polysign.common.AppClock;
import com.polysign.model.Alert;
import com.polysign.model.AlertOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sweeps closed prediction markets and writes "resolution" outcome rows.
 *
 * <p>Runs every 6 hours. For each market that has resolved, queries all alerts
 * ever fired on it via the {@code marketId-createdAt-index} GSI, then computes a
 * {@code horizon="resolution"} outcome using the final outcome price (0 or 1).
 *
 * <p><b>IMPLEMENTATION NOTE:</b> The {@link com.polysign.model.Market} bean does not
 * currently have a {@code closed} or {@code resolvedPrice} field. The production
 * {@link #findClosedMarkets()} method is therefore a stub that returns an empty list.
 * To enable production resolution sweeping, add {@code closed} (Boolean) and
 * {@code resolvedOutcomePrice} (String/BigDecimal) to {@code Market.java} and update
 * {@code MarketPoller} to persist these fields from the Gamma API response
 * ({@code active=false}, {@code closed=true}). See PROGRESS.md for the tracking note.
 *
 * <p>Package-private methods ({@link #findClosedMarkets}, {@link #findAlertsForMarket},
 * {@link #writeResolutionOutcome}) are overridable in tests.
 */
@Component
public class ResolutionSweeper {

    private static final Logger log = LoggerFactory.getLogger(ResolutionSweeper.class);

    private static final Expression HORIZON_NOT_EXISTS = Expression.builder()
            .expression("attribute_not_exists(horizon)")
            .build();

    private final AppClock                     clock;
    private final AlertOutcomeEvaluator        evaluator;
    private final DynamoDbTable<Alert>         alertsTable;
    private final DynamoDbTable<AlertOutcome>  alertOutcomesTable;

    @Autowired
    public ResolutionSweeper(
            AppClock clock,
            AlertOutcomeEvaluator evaluator,
            DynamoDbTable<Alert> alertsTable,
            DynamoDbTable<AlertOutcome> alertOutcomesTable) {
        this.clock              = clock;
        this.evaluator          = evaluator;
        this.alertsTable        = alertsTable;
        this.alertOutcomesTable = alertOutcomesTable;
    }

    // Test constructor — tables are null because subclass overrides all DB calls.
    ResolutionSweeper(AppClock clock, AlertOutcomeEvaluator evaluator) {
        this.clock              = clock;
        this.evaluator          = evaluator;
        this.alertsTable        = null;
        this.alertOutcomesTable = null;
    }

    @Scheduled(cron = "0 0 */6 * * *")
    public void run() {
        try {
            sweep();
        } catch (Exception e) {
            log.error("resolution_sweeper_failed", e);
        }
    }

    /**
     * Core sweep loop — package-private for direct invocation in tests.
     */
    void sweep() {
        Instant now = clock.now();
        List<ClosedMarket> closedMarkets = findClosedMarkets();
        int processed = 0;

        for (ClosedMarket cm : closedMarkets) {
            try {
                List<Alert> alerts = findAlertsForMarket(cm.marketId());
                for (Alert alert : alerts) {
                    processResolutionOutcome(alert, cm, now);
                }
                processed++;
                log.info("resolution_sweep_market_done marketId={} alerts={}",
                        cm.marketId(), alerts.size());
            } catch (Exception e) {
                log.warn("resolution_sweep_market_failed marketId={} error={}",
                        cm.marketId(), e.getMessage());
            }
        }

        log.info("resolution_sweep_complete markets_processed={}", processed);
    }

    private void processResolutionOutcome(Alert alert, ClosedMarket cm, Instant now) {
        String alertId = alert.getAlertId();

        // Extract firedAt and directionPredicted via evaluator logic
        // (package-private access — same package)
        BigDecimal priceAtAlert = cm.priceAtAlert();

        // Derive directionPredicted from alert metadata
        String directionPredicted = extractDirectionFromAlert(alert);

        AlertOutcome outcome = evaluator.computeOutcome(
                alertId, alert.getType(), alert.getMarketId(),
                Instant.parse(alert.getCreatedAt()),
                priceAtAlert,
                cm.resolutionPrice(),
                directionPredicted,
                "resolution",
                now,
                alert.getMetadata());

        writeResolutionOutcome(outcome);
    }

    private static String extractDirectionFromAlert(Alert alert) {
        Map<String, String> meta = alert.getMetadata();
        if (meta == null) return null;
        String type = alert.getType();
        return switch (type == null ? "" : type) {
            case "price_movement", "statistical_anomaly" -> normalizeDirection(meta.get("direction"));
            case "consensus" -> normalizeDirection(meta.get("direction"));
            case "wallet_activity" -> normalizeDirection(meta.get("side"));
            default -> null;
        };
    }

    private static String normalizeDirection(String raw) {
        if (raw == null) return null;
        return switch (raw.toUpperCase()) {
            case "BUY", "UP"    -> "up";
            case "SELL", "DOWN" -> "down";
            default             -> null;
        };
    }

    // ── Package-private seams (overridden in tests) ───────────────────────────

    /**
     * Returns closed markets with their resolution prices.
     *
     * <p><b>STUB:</b> Returns empty list because {@code Market.java} does not have a
     * {@code closed} or {@code resolvedOutcomePrice} field. Needs Market model update
     * before production use. See class Javadoc.
     */
    List<ClosedMarket> findClosedMarkets() {
        // TODO: scan markets table for closed=true once Market model has that field.
        // Example stub query:
        //   marketsTable.scan(filter: closed == true)
        //     → for each: new ClosedMarket(marketId, priceAtAlert=0.5 default, resolutionPrice)
        log.warn("resolution_sweeper_stub findClosedMarkets() returns empty — Market model has no closed field");
        return List.of();
    }

    /** Queries all alerts for a market via the marketId-createdAt-index GSI. */
    List<Alert> findAlertsForMarket(String marketId) {
        DynamoDbIndex<Alert> gsi = alertsTable.index("marketId-createdAt-index");
        QueryConditional qc = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(marketId).build());

        List<Alert> result = new ArrayList<>();
        gsi.query(r -> r.queryConditional(qc))
                .stream()
                .flatMap(page -> page.items().stream())
                .forEach(result::add);
        return result;
    }

    /** Writes a resolution outcome with attribute_not_exists(horizon) for idempotency. */
    void writeResolutionOutcome(AlertOutcome outcome) {
        try {
            alertOutcomesTable.putItem(PutItemEnhancedRequest.builder(AlertOutcome.class)
                    .item(outcome)
                    .conditionExpression(HORIZON_NOT_EXISTS)
                    .build());
            log.info("resolution_outcome_written alertId={} wasCorrect={}",
                    outcome.getAlertId(), outcome.getWasCorrect());
        } catch (ConditionalCheckFailedException e) {
            log.debug("resolution_outcome_already_exists alertId={}", outcome.getAlertId());
        }
    }

    // ── ClosedMarket record ───────────────────────────────────────────────────

    /**
     * Represents a resolved prediction market.
     *
     * @param marketId        the market's DynamoDB PK
     * @param priceAtAlertStr the approximate mid-price at alert time (string form from metadata)
     * @param resolutionPrice 1.0 if YES resolved, 0.0 if NO resolved
     */
    public record ClosedMarket(String marketId, String priceAtAlertStr, BigDecimal resolutionPrice) {
        /** Convenience: the price at alert time as BigDecimal (defaults to 0.5 if unparseable). */
        public BigDecimal priceAtAlert() {
            if (priceAtAlertStr == null) return new BigDecimal("0.50");
            try { return new BigDecimal(priceAtAlertStr); }
            catch (NumberFormatException e) { return new BigDecimal("0.50"); }
        }
    }
}
