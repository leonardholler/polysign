package com.polysign.backtest;

import com.polysign.common.AppClock;
import com.polysign.common.CategoryClassifier;
import com.polysign.model.Alert;
import com.polysign.model.AlertOutcome;
import com.polysign.model.Market;
import com.polysign.model.PriceSnapshot;
import io.micrometer.core.instrument.Counter;
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
import java.math.RoundingMode;
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
 * <p>Runs every 5 minutes. Queries the {@code nextEvaluationDue-index} GSI for PENDING
 * alerts whose next evaluation horizon is due, then evaluates all due horizons (t15m,
 * t1h, t24h). After evaluation, advances the alert's {@code nextEvaluationDue} to the
 * next horizon, or removes {@code evaluationStatus} once all horizons are complete —
 * taking the alert out of the GSI. Memory usage is O(batch_size), not O(total_alerts).
 *
 * <p>Direction predicted is derived from alert metadata (see Decision 2).
 * firedAt is read from the {@code detectedAt} metadata key (Decision 1) —
 * falls back to createdAt with a WARN log for pre-Phase-5 alerts.
 *
 * <p>Package-private methods ({@link #queryPendingAlerts}, {@link #advanceEvaluationState},
 * {@link #findClosestSnapshot}, {@link #writeOutcome}, {@link #outcomeExists}) are
 * overridable in tests via a subclass.
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
    private final DynamoDbTable<Market>        marketsTable;
    private final AppClock                     clock;
    private final MeterRegistry                meterRegistry;

    @Autowired
    public AlertOutcomeEvaluator(
            DynamoDbTable<Alert> alertsTable,
            DynamoDbTable<PriceSnapshot> snapshotsTable,
            DynamoDbTable<AlertOutcome> alertOutcomesTable,
            DynamoDbTable<Market> marketsTable,
            AppClock clock,
            MeterRegistry meterRegistry) {
        this.alertsTable        = alertsTable;
        this.snapshotsTable     = snapshotsTable;
        this.alertOutcomesTable = alertOutcomesTable;
        this.marketsTable       = marketsTable;
        this.clock              = clock;
        this.meterRegistry      = meterRegistry;
    }

    // Test constructor — tables are null because subclass overrides all DB calls.
    AlertOutcomeEvaluator(AppClock clock) {
        this.alertsTable        = null;
        this.snapshotsTable     = null;
        this.alertOutcomesTable = null;
        this.marketsTable       = null;
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
        Instant now = clock.now();
        List<Alert> alerts = queryPendingAlerts(now);
        int evaluated = 0;

        for (Alert alert : alerts) {
            try {
                evaluated += evaluateAlert(alert, now);
            } catch (Exception e) {
                log.warn("alert_outcome_eval_failed alertId={} error={}",
                        alert.getAlertId(), e.getMessage());
            }
            try {
                advanceEvaluationState(alert, now);
            } catch (Exception e) {
                log.warn("alert_evaluation_state_advance_failed alertId={} error={}",
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

        // category — resolved once per alert; falls back to CategoryClassifier if DynamoDB misses
        String category = resolveCategory(alert);

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
            outcome.setCategory(category);

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

        AlertOutcome outcome = new AlertOutcome();
        // ── Set constant fields first ─────────────────────────────────────────
        outcome.setAlertId(alertId);
        outcome.setHorizon(horizon);
        outcome.setType(type);
        outcome.setMarketId(marketId);
        outcome.setFiredAt(firedAt.toString());
        outcome.setEvaluatedAt(evaluatedAt.toString());
        outcome.setPriceAtAlert(priceAtAlert);
        outcome.setPriceAtHorizon(priceAtHorizon);
        outcome.setDirectionPredicted(directionPredicted);

        // Copy orderbook fields from alert metadata (informational, not used in precision)
        if (alertMetadata != null) {
            parseAndSet(alertMetadata.get("spreadBps"), outcome::setSpreadBpsAtAlert);
            parseAndSet(alertMetadata.get("depthAtMid"), outcome::setDepthAtMidAtAlert);
        }

        // ── Part 1: scorable check ────────────────────────────────────────────
        // An alert is UNSCORABLE when priceAtAlert is null or zero — no Brier baseline.
        // null priceAtAlert → no direction computation at all (missing pre-deploy data).
        // zero priceAtAlert → scorable=false (no Brier), but for resolution horizon the
        //   direction is still derived from priceAtHorizon (zero is a valid resolved-NO price).
        boolean isNull = (priceAtAlert == null);
        boolean isZero = !isNull && priceAtAlert.compareTo(BigDecimal.ZERO) == 0;
        boolean isUnscorable = isNull || isZero;
        boolean isResolution = "resolution".equals(horizon);

        if (isNull) {
            // Truly missing baseline — no direction computation possible
            outcome.setScorable(false);
            outcome.setSkipReason("no_baseline");
            // wasCorrect, directionRealized, magnitudePp all remain null
            return outcome;
        }

        // ── Part 2: dead-zone flag ────────────────────────────────────────────
        // Dead zone: priceAtAlert < 0.10 or > 0.90 — outcome near-predetermined.
        // Not applicable when priceAtAlert is zero (degenerate, already set unscorable).
        boolean dz = !isUnscorable
                && (priceAtAlert.compareTo(BigDecimal.valueOf(0.10)) < 0
                 || priceAtAlert.compareTo(BigDecimal.valueOf(0.90)) > 0);
        outcome.setScorable(!isUnscorable);
        if (isUnscorable) {
            outcome.setSkipReason("no_baseline");
        }
        outcome.setDeadZone(dz);

        // ── Decision 4: rawDelta + magnitudePp ───────────────────────────────
        BigDecimal rawDelta = isUnscorable
                ? BigDecimal.ZERO
                : priceAtHorizon.subtract(priceAtAlert);

        if (!isUnscorable) {
            BigDecimal magnitudePp;
            if (directionPredicted == null) {
                magnitudePp = rawDelta.abs();
            } else if ("up".equals(directionPredicted)) {
                magnitudePp = rawDelta;
            } else {
                // "down"
                magnitudePp = rawDelta.negate();
            }
            outcome.setMagnitudePp(magnitudePp);
        }

        // ── Decision 4: directionRealized + wasCorrect ───────────────────────
        String  directionRealized;
        Boolean wasCorrect;

        if (isResolution) {
            // For resolution horizon, derive directionRealized from the final market price.
            // Magnitude thresholds must NOT be used here: by the time the sweeper runs,
            // currentYesPrice has already settled at 1.0 or 0.0, so rawDelta ≈ 0 and
            // every row would be scored "flat" by the normal dead-zone check.
            if (priceAtHorizon.compareTo(BigDecimal.valueOf(0.99)) >= 0) {
                directionRealized = "up";
            } else if (priceAtHorizon.compareTo(BigDecimal.valueOf(0.01)) <= 0) {
                directionRealized = "down";
            } else {
                directionRealized = "flat"; // price stuck mid-range — not yet decisive
            }
            wasCorrect = (directionPredicted != null && !"flat".equals(directionRealized))
                    ? directionPredicted.equals(directionRealized)
                    : null;
        } else if (directionPredicted == null) {
            directionRealized = null;
            wasCorrect        = null;
        } else if (rawDelta.compareTo(BigDecimal.valueOf(0.005)) > 0) {
            directionRealized = "up";
            wasCorrect        = "up".equals(directionPredicted);
        } else if (rawDelta.compareTo(BigDecimal.valueOf(-0.005)) < 0) {
            directionRealized = "down";
            wasCorrect        = "down".equals(directionPredicted);
        } else {
            // Movement dead zone: |rawDelta| < 0.005 — excluded from precision denominator
            directionRealized = "flat";
            wasCorrect        = null;
        }
        outcome.setDirectionRealized(directionRealized);
        outcome.setWasCorrect(wasCorrect);

        // ── Part 3: Brier skill score ────────────────────────────────────────
        // Only computable when a direction was predicted and priceAtAlert is a valid baseline.
        // actual = 1.0 when priceAtHorizon ≥ 0.50 (market says YES probable), else 0.0.
        // detectorProbYes = priceAtAlert ± 0.20 (detector's shifted belief).
        if (directionPredicted != null && !isUnscorable) {
            double actual         = priceAtHorizon.doubleValue() >= 0.50 ? 1.0 : 0.0;
            double mktProb        = priceAtAlert.doubleValue();
            double detProb        = "up".equals(directionPredicted)
                    ? Math.min(mktProb + 0.20, 0.99)
                    : Math.max(mktProb - 0.20, 0.01);
            double mb = (mktProb - actual) * (mktProb - actual);
            double db = (detProb  - actual) * (detProb  - actual);
            outcome.setMarketBrier(BigDecimal.valueOf(mb).setScale(6, RoundingMode.HALF_UP));
            outcome.setDetectorBrier(BigDecimal.valueOf(db).setScale(6, RoundingMode.HALF_UP));
            outcome.setBrierSkill(BigDecimal.valueOf(mb - db).setScale(6, RoundingMode.HALF_UP));
        }

        return outcome;
    }

    // ── Category resolution ───────────────────────────────────────────────────

    private String resolveCategory(Alert alert) {
        String marketId = alert.getMarketId();
        if (marketId == null) return null;

        // Step 1: DynamoDB lookup
        String category = null;
        if (marketsTable != null) {
            try {
                Market market = marketsTable.getItem(Key.builder().partitionValue(marketId).build());
                category = market != null ? market.getCategory() : null;
            } catch (Exception e) {
                log.debug("alert_outcome_category_resolve_failed marketId={} error={}", marketId, e.getMessage());
            }
        }

        if (category != null) return category;

        // Step 2: DynamoDB miss or unavailable — fall back to CategoryClassifier
        try {
            String classified = CategoryClassifier.classify(alert.getTitle(), null);
            incrementCategoryFallbackCounter();
            return classified;
        } catch (Exception e) {
            log.warn("category_resolution_unresolvable alertId={} marketId={} reason={}",
                    alert.getAlertId(), marketId, e.getMessage());
        }

        return "unknown";
    }

    private void incrementCategoryFallbackCounter() {
        if (meterRegistry == null) return;
        Counter.builder("polysign.category_resolution.fallback_used")
                .register(meterRegistry)
                .increment();
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

    static Instant extractFiredAt(Alert alert) {
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
     * Queries the {@code nextEvaluationDue-index} GSI for alerts with
     * {@code evaluationStatus = "PENDING"} and {@code nextEvaluationDue <= now}.
     * Returns only the alerts whose next evaluation horizon has arrived.
     * Memory usage is O(due_alerts), not O(total_alerts_ever_written).
     */
    List<Alert> queryPendingAlerts(Instant now) {
        DynamoDbIndex<Alert> gsi = alertsTable.index("nextEvaluationDue-index");
        QueryConditional qc = QueryConditional.sortLessThanOrEqualTo(
                Key.builder()
                        .partitionValue("PENDING")
                        .sortValue(now.toString())
                        .build());

        List<Alert> result = new ArrayList<>();
        gsi.query(r -> r.queryConditional(qc))
                .stream()
                .flatMap(page -> page.items().stream())
                .forEach(result::add);
        return result;
    }

    /**
     * Advances the alert's evaluation state after a round of horizon evaluations.
     *
     * <ul>
     *   <li>If all horizons (t15m, t1h, t24h) are now past: sets {@code evaluationStatus}
     *       and {@code nextEvaluationDue} to null, removing the alert from the GSI.</li>
     *   <li>If t1h is past but t24h is not: advances {@code nextEvaluationDue} to firedAt+24h.</li>
     *   <li>If only t15m is past: advances {@code nextEvaluationDue} to firedAt+1h.</li>
     * </ul>
     *
     * <p>Always calls {@code alertsTable.updateItem} so the GSI stays consistent.
     */
    void advanceEvaluationState(Alert alert, Instant now) {
        Instant firedAt = extractFiredAt(alert);
        Instant t1hDue  = firedAt.plus(Duration.ofHours(1));
        Instant t24hDue = firedAt.plus(Duration.ofHours(24));

        if (!now.isBefore(t24hDue)) {
            // All horizons complete — remove from GSI by deleting the GSI attributes.
            alert.setEvaluationStatus(null);
            alert.setNextEvaluationDue(null);
        } else if (!now.isBefore(t1hDue)) {
            // t1h just completed; next is t24h.
            alert.setNextEvaluationDue(t24hDue.toString());
        } else {
            // t15m just completed; next is t1h.
            alert.setNextEvaluationDue(t1hDue.toString());
        }
        alertsTable.updateItem(alert);
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
