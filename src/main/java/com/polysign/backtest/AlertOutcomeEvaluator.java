package com.polysign.backtest;

import com.polysign.common.AppClock;
import com.polysign.model.Alert;
import com.polysign.model.AlertOutcome;
import com.polysign.model.PriceSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Evaluates fired alerts against forward price movement at fixed horizons.
 *
 * <p>Runs every 5 minutes. For each alert fired between 15 minutes ago and
 * 25 hours ago, evaluates whichever of the t15m / t1h / t24h horizons are
 * now due and whose outcome row does not yet exist in {@code alert_outcomes}.
 *
 * <p>Direction predicted is derived from alert metadata (see Decision 2).
 * firedAt is read from the {@code detectedAt} metadata key (Decision 1) —
 * falls back to createdAt with a WARN log for pre-Phase-5 alerts.
 *
 * <p>Package-private methods ({@link #scanRecentAlerts}, {@link #findClosestSnapshot},
 * {@link #writeOutcome}, {@link #outcomeExists}) are overridable in tests via a subclass.
 *
 * <p>{@link #computeOutcome} is package-private and reused by {@link ResolutionSweeper}.
 */
@Component
public class AlertOutcomeEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertOutcomeEvaluator.class);

    private static final List<HorizonConfig> HORIZONS = List.of(
            new HorizonConfig("t15m", Duration.ofMinutes(15)),
            new HorizonConfig("t1h",  Duration.ofHours(1)),
            new HorizonConfig("t24h", Duration.ofHours(24))
    );

    private static final Expression HORIZON_NOT_EXISTS = Expression.builder()
            .expression("attribute_not_exists(horizon)")
            .build();

    private final DynamoDbTable<Alert>        alertsTable;
    private final DynamoDbTable<PriceSnapshot> snapshotsTable;
    private final DynamoDbTable<AlertOutcome>  alertOutcomesTable;
    private final AppClock                     clock;
    private final MeterRegistry                meterRegistry;

    @Autowired
    public AlertOutcomeEvaluator(
            DynamoDbTable<Alert> alertsTable,
            DynamoDbTable<PriceSnapshot> snapshotsTable,
            DynamoDbTable<AlertOutcome> alertOutcomesTable,
            AppClock clock,
            MeterRegistry meterRegistry) {
        this.alertsTable        = alertsTable;
        this.snapshotsTable     = snapshotsTable;
        this.alertOutcomesTable = alertOutcomesTable;
        this.clock              = clock;
        this.meterRegistry      = meterRegistry;
    }

    // Test constructor — tables are null because subclass overrides all DB calls.
    AlertOutcomeEvaluator(AppClock clock) {
        this.alertsTable        = null;
        this.snapshotsTable     = null;
        this.alertOutcomesTable = null;
        this.clock              = clock;
        this.meterRegistry      = null;
    }

    @Scheduled(cron = "0 */5 * * * *")
    public void run() {
        try {
            evaluate();
        } catch (Exception e) {
            log.error("alert_outcome_evaluator_failed", e);
        }
    }

    /**
     * Core evaluation loop — public for direct invocation in integration tests.
     */
    public void evaluate() {
        Instant now      = clock.now();
        Instant earliest = now.minus(Duration.ofHours(25));
        Instant latest   = now.minus(Duration.ofMinutes(15));

        List<Alert> alerts = scanRecentAlerts(earliest, latest);
        int evaluated = 0;

        for (Alert alert : alerts) {
            try {
                evaluated += evaluateAlert(alert, now);
            } catch (Exception e) {
                log.warn("alert_outcome_eval_failed alertId={} error={}",
                        alert.getAlertId(), e.getMessage());
            }
        }

        log.info("alert_outcome_evaluate_complete alerts={} outcomes_written={}",
                alerts.size(), evaluated);
    }

    /**
     * Evaluate a single alert at all due horizons.
     * Returns the number of outcome rows written.
     */
    private int evaluateAlert(Alert alert, Instant now) {
        String alertId = alert.getAlertId();
        Instant firedAt = extractFiredAt(alert);

        String directionPredicted = extractDirectionPredicted(alert);

        // priceAtAlert — from the snapshot closest to firedAt
        Optional<PriceSnapshot> alertSnap = findClosestSnapshot(alert.getMarketId(), firedAt);
        if (alertSnap.isEmpty()) {
            log.debug("alert_outcome_skip_no_alert_price alertId={}", alertId);
            return 0;
        }
        BigDecimal priceAtAlert = alertSnap.get().getMidpoint();

        int written = 0;
        for (HorizonConfig hc : HORIZONS) {
            Instant horizonInstant = firedAt.plus(hc.duration());
            if (horizonInstant.isAfter(now)) {
                continue; // horizon not due yet
            }
            if (outcomeExists(alertId, hc.label())) {
                log.debug("alert_outcome_already_exists alertId={} horizon={}", alertId, hc.label());
                continue;
            }

            Optional<PriceSnapshot> horizonSnap = findClosestSnapshot(alert.getMarketId(), horizonInstant);
            if (horizonSnap.isEmpty()) {
                log.debug("alert_outcome_skip_no_horizon_snapshot alertId={} horizon={}", alertId, hc.label());
                continue;
            }
            BigDecimal priceAtHorizon = horizonSnap.get().getMidpoint();

            AlertOutcome outcome = computeOutcome(
                    alertId, alert.getType(), alert.getMarketId(),
                    firedAt, priceAtAlert, priceAtHorizon,
                    directionPredicted, hc.label(), now, alert.getMetadata());

            writeOutcome(outcome);
            emitMetric(alert.getType(), hc.label());
            written++;
        }
        return written;
    }

    /**
     * Computes an {@link AlertOutcome} from the given inputs.
     *
     * <p>Package-private so {@link ResolutionSweeper} can reuse this logic
     * for the "resolution" horizon with the final market price as priceAtHorizon.
     */
    AlertOutcome computeOutcome(String alertId, String type, String marketId,
                                Instant firedAt, BigDecimal priceAtAlert,
                                BigDecimal priceAtHorizon, String directionPredicted,
                                String horizon, Instant evaluatedAt,
                                Map<String, String> alertMetadata) {

        BigDecimal rawDelta = priceAtHorizon.subtract(priceAtAlert);

        // Decision 4: magnitudePp formula
        BigDecimal magnitudePp;
        if (directionPredicted == null) {
            magnitudePp = rawDelta.abs();
        } else if ("up".equals(directionPredicted)) {
            magnitudePp = rawDelta;
        } else {
            // "down"
            magnitudePp = rawDelta.negate();
        }

        // Decision 4: directionRealized + wasCorrect
        String  directionRealized;
        Boolean wasCorrect;

        if (directionPredicted == null) {
            directionRealized = null;
            wasCorrect        = null;
        } else if (rawDelta.compareTo(BigDecimal.valueOf(0.005)) > 0) {
            directionRealized = "up";
            wasCorrect        = "up".equals(directionPredicted);
        } else if (rawDelta.compareTo(BigDecimal.valueOf(-0.005)) < 0) {
            directionRealized = "down";
            wasCorrect        = "down".equals(directionPredicted);
        } else {
            // Dead zone: |rawDelta| < 0.005 — excluded from precision denominator
            directionRealized = "flat";
            wasCorrect        = null;
        }

        AlertOutcome outcome = new AlertOutcome();
        outcome.setAlertId(alertId);
        outcome.setHorizon(horizon);
        outcome.setType(type);
        outcome.setMarketId(marketId);
        outcome.setFiredAt(firedAt.toString());
        outcome.setEvaluatedAt(evaluatedAt.toString());
        outcome.setPriceAtAlert(priceAtAlert);
        outcome.setPriceAtHorizon(priceAtHorizon);
        outcome.setDirectionPredicted(directionPredicted);
        outcome.setDirectionRealized(directionRealized);
        outcome.setWasCorrect(wasCorrect);
        outcome.setMagnitudePp(magnitudePp);

        // Copy orderbook fields from alert metadata (informational, not used in precision)
        if (alertMetadata != null) {
            parseAndSet(alertMetadata.get("spreadBps"), outcome::setSpreadBpsAtAlert);
            parseAndSet(alertMetadata.get("depthAtMid"), outcome::setDepthAtMidAtAlert);
        }

        return outcome;
    }

    // ── Direction extraction (Decision 2) ─────────────────────────────────────

    private static String extractDirectionPredicted(Alert alert) {
        String type = alert.getType();
        Map<String, String> meta = alert.getMetadata();
        if (meta == null) return null;

        return switch (type == null ? "" : type) {
            case "price_movement", "statistical_anomaly" ->
                    normalizeDirection(meta.get("direction"));
            case "consensus" ->
                    normalizeDirection(meta.get("direction"));
            case "wallet_activity" ->
                    normalizeDirection(meta.get("side"));
            default ->
                    null; // news_correlation and unknown types have no direction prediction
        };
    }

    /**
     * Normalises raw metadata direction strings to "up" / "down" / null.
     * Handles "BUY"/"SELL" from wallet detectors and "up"/"down" from price detectors.
     */
    private static String normalizeDirection(String raw) {
        if (raw == null) return null;
        return switch (raw.toUpperCase()) {
            case "BUY", "UP"   -> "up";
            case "SELL", "DOWN" -> "down";
            default             -> null;
        };
    }

    // ── firedAt extraction (Decision 1) ──────────────────────────────────────

    private static Instant extractFiredAt(Alert alert) {
        Map<String, String> meta = alert.getMetadata();
        if (meta != null && meta.containsKey("detectedAt")) {
            try {
                return Instant.parse(meta.get("detectedAt"));
            } catch (Exception e) {
                log.warn("alert_outcome_firedAt_parse_failed alertId={} detectedAt={} using createdAt",
                        alert.getAlertId(), meta.get("detectedAt"));
            }
        }
        log.warn("alert_outcome_firedAt_fallback alertId={} using createdAt", alert.getAlertId());
        return Instant.parse(alert.getCreatedAt());
    }

    // ── Package-private seams (overridden in tests) ───────────────────────────

    /**
     * Scans the alerts table for alerts with createdAt between earliest and latest.
     * Decision 5: a filtered scan is correct at this project's scale.
     */
    List<Alert> scanRecentAlerts(Instant earliest, Instant latest) {
        Expression filter = Expression.builder()
                .expression("#ca >= :earliest AND #ca <= :latest")
                .expressionNames(Map.of("#ca", "createdAt"))
                .expressionValues(Map.of(
                        ":earliest", AttributeValue.fromS(earliest.toString()),
                        ":latest",   AttributeValue.fromS(latest.toString())))
                .build();

        List<Alert> result = new ArrayList<>();
        alertsTable.scan(ScanEnhancedRequest.builder()
                        .filterExpression(filter)
                        .build())
                .items()
                .forEach(result::add);
        return result;
    }

    /**
     * Finds the PriceSnapshot closest to target (within ±2 minutes).
     * Returns empty if no snapshot exists in the window.
     */
    Optional<PriceSnapshot> findClosestSnapshot(String marketId, Instant target) {
        Instant from = target.minus(Duration.ofMinutes(2));
        Instant to   = target.plus(Duration.ofMinutes(2));

        var qc = software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional.sortBetween(
                Key.builder().partitionValue(marketId).sortValue(from.toString()).build(),
                Key.builder().partitionValue(marketId).sortValue(to.toString()).build());

        List<PriceSnapshot> candidates = snapshotsTable
                .query(r -> r.queryConditional(qc))
                .items()
                .stream()
                .toList();

        if (candidates.isEmpty()) return Optional.empty();

        return candidates.stream()
                .min(Comparator.comparingLong(s ->
                        Math.abs(Instant.parse(s.getTimestamp()).getEpochSecond()
                                - target.getEpochSecond())));
    }

    /** Writes an outcome row with attribute_not_exists(horizon) for idempotency. */
    void writeOutcome(AlertOutcome outcome) {
        try {
            alertOutcomesTable.putItem(PutItemEnhancedRequest.builder(AlertOutcome.class)
                    .item(outcome)
                    .conditionExpression(HORIZON_NOT_EXISTS)
                    .build());
            log.info("alert_outcome_written alertId={} horizon={} wasCorrect={}",
                    outcome.getAlertId(), outcome.getHorizon(), outcome.getWasCorrect());
        } catch (ConditionalCheckFailedException e) {
            log.debug("alert_outcome_already_exists_on_write alertId={} horizon={}",
                    outcome.getAlertId(), outcome.getHorizon());
        }
    }

    /** Returns true if an outcome row already exists for (alertId, horizon). */
    boolean outcomeExists(String alertId, String horizon) {
        AlertOutcome existing = alertOutcomesTable.getItem(Key.builder()
                .partitionValue(alertId)
                .sortValue(horizon)
                .build());
        return existing != null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void emitMetric(String type, String horizon) {
        if (meterRegistry == null) return;
        Counter.builder("polysign.outcomes.evaluated")
                .tag("type", type != null ? type : "unknown")
                .tag("horizon", horizon)
                .register(meterRegistry)
                .increment();
    }

    private static void parseAndSet(String raw, java.util.function.Consumer<BigDecimal> setter) {
        if (raw == null) return;
        try {
            setter.accept(new BigDecimal(raw));
        } catch (NumberFormatException ignored) {
            // skip bad metadata value
        }
    }

    record HorizonConfig(String label, Duration duration) {}
}
