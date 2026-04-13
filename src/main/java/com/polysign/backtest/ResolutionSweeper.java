package com.polysign.backtest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polysign.common.AppClock;
import com.polysign.model.Alert;
import com.polysign.model.AlertOutcome;
import com.polysign.model.Market;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sweeps closed prediction markets and writes "resolution" outcome rows.
 *
 * <p>Runs every 6 hours. Each run has two phases:
 * <ol>
 *   <li><b>Poll phase</b> — calls {@link #pollAndStoreClosedMarkets()} to fetch
 *       {@code closed=true} markets from the Gamma API and write
 *       {@code resolvedOutcomePrice} (parsed from {@code outcomePrices[0]}) onto any
 *       tracked {@code Market} row that does not yet have one set.</li>
 *   <li><b>Sweep phase</b> — calls {@link #findClosedMarkets()} to scan DynamoDB for
 *       markets where {@code resolvedOutcomePrice} is now populated, then for each
 *       market queries all alerts via the {@code marketId-createdAt-index} GSI and
 *       writes a {@code horizon="resolution"} outcome row via
 *       {@link AlertOutcomeEvaluator#computeOutcome}.</li>
 * </ol>
 *
 * <p>Package-private methods ({@link #pollAndStoreClosedMarkets},
 * {@link #findClosedMarkets}, {@link #findAlertsForMarket},
 * {@link #writeResolutionOutcome}) are overridable in tests.
 */
@Component
public class ResolutionSweeper {

    private static final Logger log = LoggerFactory.getLogger(ResolutionSweeper.class);

    private static final int GAMMA_PAGE_LIMIT = 200;

    private static final Expression HAS_RESOLUTION_PRICE = Expression.builder()
            .expression("attribute_exists(resolvedOutcomePrice)")
            .build();

    private static final Expression HORIZON_NOT_EXISTS = Expression.builder()
            .expression("attribute_not_exists(horizon)")
            .build();

    private final AppClock                     clock;
    private final AlertOutcomeEvaluator        evaluator;
    private final DynamoDbTable<Alert>         alertsTable;
    private final DynamoDbTable<AlertOutcome>  alertOutcomesTable;
    private final DynamoDbTable<Market>        marketsTable;
    private final WebClient                    gammaClient;
    private final ObjectMapper                 objectMapper;

    @Autowired
    public ResolutionSweeper(
            AppClock clock,
            AlertOutcomeEvaluator evaluator,
            DynamoDbTable<Alert> alertsTable,
            DynamoDbTable<AlertOutcome> alertOutcomesTable,
            DynamoDbTable<Market> marketsTable,
            @Qualifier("gammaApiClient") WebClient gammaClient,
            ObjectMapper objectMapper) {
        this.clock              = clock;
        this.evaluator          = evaluator;
        this.alertsTable        = alertsTable;
        this.alertOutcomesTable = alertOutcomesTable;
        this.marketsTable       = marketsTable;
        this.gammaClient        = gammaClient;
        this.objectMapper       = objectMapper;
    }

    // Test constructor — DB tables and HTTP client are null; subclass overrides all seams.
    ResolutionSweeper(AppClock clock, AlertOutcomeEvaluator evaluator) {
        this.clock              = clock;
        this.evaluator          = evaluator;
        this.alertsTable        = null;
        this.alertOutcomesTable = null;
        this.marketsTable       = null;
        this.gammaClient        = null;
        this.objectMapper       = null;
    }

    /**
     * Runs one sweep immediately after the application context is fully started.
     * This ensures resolution data is populated right after every deploy rather than
     * waiting up to 6 hours for the first scheduled cron fire.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("resolution_sweeper_startup_run starting initial sweep after application ready");
        try {
            sweep();
        } catch (Exception e) {
            log.error("resolution_sweeper_startup_failed", e);
        }
    }

    /** @Scheduled interval: every 6 hours (0:00, 6:00, 12:00, 18:00 UTC). */
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
        pollAndStoreClosedMarkets();

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

        BigDecimal priceAtAlert = cm.priceAtAlert();
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
     * Polls the Gamma API for closed markets and writes {@code resolvedOutcomePrice}
     * onto any tracked {@code Market} row that does not yet have one.
     *
     * <p>Resolution price is taken from {@code outcomePrices[0]} in the Gamma response —
     * this is the YES-outcome final price: 1.0 if YES resolved, 0.0 if NO resolved.
     *
     * <p>No-op when {@code gammaClient} or {@code marketsTable} is null (test mode).
     */
    void pollAndStoreClosedMarkets() {
        if (gammaClient == null || marketsTable == null || objectMapper == null) return;

        int updated = 0;
        int offset = 0;

        while (true) {
            List<Map<String, Object>> page = fetchClosedMarketsPage(offset);
            if (page.isEmpty()) break;

            for (Map<String, Object> item : page) {
                try {
                    String marketId = stringOrNull(item, "id");
                    if (marketId == null || marketId.isBlank()) continue;

                    // Only update markets already tracked in DynamoDB
                    Key key = Key.builder().partitionValue(marketId).build();
                    Market existing = marketsTable.getItem(key);
                    if (existing == null) continue;
                    if (existing.getResolvedOutcomePrice() != null) continue; // idempotent

                    // outcomePrices is a JSON-array string: ["1","0"] = YES won, ["0","1"] = NO won
                    List<String> outcomePrices = parseJsonStringList(item, "outcomePrices");
                    if (outcomePrices.isEmpty()) {
                        log.debug("resolution_no_outcome_prices marketId={}", marketId);
                        continue;
                    }

                    BigDecimal resolutionPrice;
                    try {
                        resolutionPrice = new BigDecimal(outcomePrices.get(0));
                    } catch (NumberFormatException e) {
                        log.warn("resolution_price_parse_failed marketId={} raw={}",
                                marketId, outcomePrices.get(0));
                        continue;
                    }

                    existing.setResolvedOutcomePrice(resolutionPrice);
                    existing.setClosed(true);
                    existing.setUpdatedAt(clock.nowIso());
                    marketsTable.putItem(existing);

                    log.info("resolution_applied marketId={} source=gamma_closed_markets_poll price={}",
                            marketId, resolutionPrice);
                    updated++;

                } catch (Exception e) {
                    log.warn("resolution_market_update_failed marketId={} error={}",
                            item.getOrDefault("id", "unknown"), e.getMessage());
                }
            }

            if (page.size() < GAMMA_PAGE_LIMIT) break;
            offset += GAMMA_PAGE_LIMIT;
        }

        log.info("resolution_poll_complete markets_updated={}", updated);
    }

    /**
     * Returns closed markets with their resolution prices by scanning DynamoDB for
     * markets where {@code resolvedOutcomePrice} has been populated.
     */
    List<ClosedMarket> findClosedMarkets() {
        if (marketsTable == null) return List.of();

        List<ClosedMarket> result = new ArrayList<>();
        try {
            marketsTable.scan(ScanEnhancedRequest.builder()
                            .filterExpression(HAS_RESOLUTION_PRICE)
                            .build())
                    .items()
                    .forEach(m -> {
                        String priceAtAlertStr = m.getCurrentYesPrice() != null
                                ? m.getCurrentYesPrice().toPlainString() : "0.50";
                        result.add(new ClosedMarket(
                                m.getMarketId(), priceAtAlertStr, m.getResolvedOutcomePrice()));
                    });
        } catch (Exception e) {
            log.warn("resolution_sweeper_scan_failed error={}", e.getMessage());
        }
        return result;
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

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<Map<String, Object>> fetchClosedMarketsPage(int offset) {
        try {
            String body = gammaClient.get()
                    .uri(u -> u.path("/markets")
                               .queryParam("closed", "true")
                               .queryParam("limit",  GAMMA_PAGE_LIMIT)
                               .queryParam("offset", offset)
                               .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("resolution_gamma_fetch_failed offset={} error={}", offset, e.getMessage());
            return List.of();
        }
    }

    private List<String> parseJsonStringList(Map<String, Object> item, String fieldName) {
        Object raw = item.get(fieldName);
        if (raw == null) return List.of();
        String s = raw.toString().trim();
        if (s.isBlank() || "null".equals(s)) return List.of();
        try {
            return objectMapper.readValue(s, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("resolution_json_list_parse_failed field={} value={}", fieldName, s);
            return List.of();
        }
    }

    private static String stringOrNull(Map<String, Object> item, String key) {
        Object v = item.get(key);
        return v == null ? null : v.toString();
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
