package com.polysign.detector;

import com.polysign.alert.AlertIdFactory;
import com.polysign.alert.AlertService;
import com.polysign.common.AppClock;
import com.polysign.common.CorrelationId;
import com.polysign.model.Alert;
import com.polysign.model.Market;
import com.polysign.model.PriceSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Threshold-based price movement detector.
 *
 * <p>Runs every 60 seconds. For each active market, queries the last 60 minutes
 * of snapshots and fires a {@code price_movement} alert if price moved
 * &ge; {@code thresholdPct} within any {@code windowMinutes}-minute span,
 * provided 24h volume &ge; {@code minVolumeUsdc}.
 *
 * <p>Dedupe: 30-minute bucketed window via {@link AlertIdFactory}. Bypassed
 * (unbucketed) if the move exceeds 2&times; the threshold.
 */
@Component
public class PriceMovementDetector {

    private static final Logger log = LoggerFactory.getLogger(PriceMovementDetector.class);
    private static final String ALERT_TYPE = "price_movement";
    private static final int LOOKBACK_MINUTES = 60;

    private final DynamoDbTable<Market> marketsTable;
    private final DynamoDbTable<PriceSnapshot> snapshotsTable;
    private final AlertService alertService;
    private final AppClock clock;

    private final double thresholdPct;
    private final int windowMinutes;
    private final double minVolumeUsdc;
    private final Duration dedupeWindow;

    public PriceMovementDetector(
            DynamoDbTable<Market> marketsTable,
            DynamoDbTable<PriceSnapshot> snapshotsTable,
            AlertService alertService,
            AppClock clock,
            @Value("${polysign.detectors.price.threshold-pct:8.0}") double thresholdPct,
            @Value("${polysign.detectors.price.window-minutes:15}") int windowMinutes,
            @Value("${polysign.detectors.price.min-volume-usdc:50000}") double minVolumeUsdc,
            @Value("${polysign.detectors.price.dedupe-window-minutes:30}") int dedupeWindowMinutes) {
        this.marketsTable = marketsTable;
        this.snapshotsTable = snapshotsTable;
        this.alertService = alertService;
        this.clock = clock;
        this.thresholdPct = thresholdPct;
        this.windowMinutes = windowMinutes;
        this.minVolumeUsdc = minVolumeUsdc;
        this.dedupeWindow = Duration.ofMinutes(dedupeWindowMinutes);
    }

    @Scheduled(fixedDelayString = "${polysign.detectors.price.interval-ms:60000}",
               initialDelayString = "${polysign.detectors.price.initial-delay-ms:65000}")
    public void run() {
        try (var ignored = CorrelationId.set()) {
            detect();
        } catch (Exception e) {
            log.error("price_movement_detector_failed", e);
        }
    }

    /**
     * Core detection loop — package-private for direct invocation in tests.
     */
    void detect() {
        Instant now = clock.now();
        int checked = 0;
        int fired = 0;

        for (Market market : marketsTable.scan().items()) {
            try {
                if (checkMarket(market, now)) {
                    fired++;
                }
                checked++;
            } catch (Exception e) {
                log.warn("price_movement_check_failed marketId={} error={}",
                        market.getMarketId(), e.getMessage());
            }
        }

        log.info("price_movement_detect_complete checked={} fired={}", checked, fired);
    }

    /**
     * Check a single market for price movement. Returns true if an alert was
     * created (new), false otherwise (no movement, filtered, or deduplicated).
     *
     * <p>Package-private so unit tests can call it directly with synthetic data
     * without needing a full DynamoDB scan.
     */
    boolean checkMarket(Market market, Instant now) {
        // Volume gate
        double volume24h = parseVolume(market.getVolume24h());
        if (volume24h < minVolumeUsdc) {
            return false;
        }

        // Query snapshots for the last LOOKBACK_MINUTES
        List<PriceSnapshot> snapshots = querySnapshots(market.getMarketId(), now);
        if (snapshots.size() < 2) {
            return false;
        }

        // Find the maximum absolute move within any windowMinutes-minute span
        MoveResult move = findMaxMove(snapshots);
        if (move == null) {
            return false;
        }

        double movePct = move.pctChange;
        if (movePct < thresholdPct) {
            return false;
        }

        // Determine severity and dedupe behavior
        boolean watched = Boolean.TRUE.equals(market.getIsWatched());
        String severity = watched ? "critical" : "warning";
        boolean bypassDedupe = movePct >= thresholdPct * 2;
        Duration effectiveWindow = bypassDedupe ? Duration.ZERO : dedupeWindow;

        String alertId = AlertIdFactory.generate(ALERT_TYPE, market.getMarketId(),
                now, effectiveWindow);

        // createdAt must be deterministic for the same alertId so that the
        // composite key (PK=alertId, SK=createdAt) targets the same DynamoDB
        // item on every detector cycle. Without this, attribute_not_exists(alertId)
        // would see a new (PK,SK) slot each second and never reject duplicates.
        Instant bucketedAt = AlertIdFactory.bucketedInstant(now, effectiveWindow);

        String direction = move.toPrice.compareTo(move.fromPrice) >= 0 ? "up" : "down";

        Alert alert = new Alert();
        alert.setAlertId(alertId);
        alert.setCreatedAt(bucketedAt.toString());
        alert.setType(ALERT_TYPE);
        alert.setSeverity(severity);
        alert.setMarketId(market.getMarketId());
        alert.setTitle(String.format("%.1f%% price move %s", movePct, direction));
        alert.setDescription(String.format("%s moved %.1f%% %s in %d min (%.4f → %.4f)",
                market.getQuestion() != null ? market.getQuestion() : market.getMarketId(),
                movePct, direction, move.spanMinutes, move.fromPrice, move.toPrice));
        alert.setLink("https://polymarket.com/event/" + market.getMarketId());
        alert.setMetadata(Map.of(
                "movePct", String.format("%.2f", movePct),
                "fromPrice", move.fromPrice.toPlainString(),
                "toPrice", move.toPrice.toPlainString(),
                "direction", direction,
                "spanMinutes", String.valueOf(move.spanMinutes),
                "volume24h", String.format("%.0f", volume24h),
                "isWatched", String.valueOf(watched),
                "bypassedDedupe", String.valueOf(bypassDedupe)
        ));

        return alertService.tryCreate(alert);
    }

    /**
     * Find the maximum absolute percentage move between any two snapshots
     * that are within {@code windowMinutes} of each other.
     *
     * <p>Snapshots must be sorted ascending by timestamp. Returns null if
     * no valid pair exists.
     */
    MoveResult findMaxMove(List<PriceSnapshot> snapshots) {
        MoveResult best = null;

        for (int j = 1; j < snapshots.size(); j++) {
            PriceSnapshot later = snapshots.get(j);
            Instant laterTime = Instant.parse(later.getTimestamp());

            for (int i = j - 1; i >= 0; i--) {
                PriceSnapshot earlier = snapshots.get(i);
                Instant earlierTime = Instant.parse(earlier.getTimestamp());

                long spanMinutes = Duration.between(earlierTime, laterTime).toMinutes();
                if (spanMinutes > windowMinutes) {
                    break; // sorted ascending, so all further i are even older
                }

                double pct = pctChange(earlier.getMidpoint(), later.getMidpoint());
                if (best == null || pct > best.pctChange) {
                    best = new MoveResult(earlier.getMidpoint(), later.getMidpoint(),
                            pct, spanMinutes);
                }
            }
        }

        return best;
    }

    List<PriceSnapshot> querySnapshots(String marketId, Instant now) {
        String from = now.minus(Duration.ofMinutes(LOOKBACK_MINUTES)).toString();
        String to = now.toString();

        var qc = QueryConditional.sortBetween(
                Key.builder().partitionValue(marketId).sortValue(from).build(),
                Key.builder().partitionValue(marketId).sortValue(to).build());

        return snapshotsTable.query(r -> r.queryConditional(qc).scanIndexForward(true))
                .items()
                .stream()
                .toList();
    }

    private static double pctChange(BigDecimal from, BigDecimal to) {
        if (from.signum() == 0) return 0.0;
        return to.subtract(from)
                .abs()
                .divide(from, MathContext.DECIMAL64)
                .doubleValue() * 100.0;
    }

    private static double parseVolume(String raw) {
        if (raw == null || raw.isBlank()) return 0.0;
        try { return Double.parseDouble(raw); } catch (NumberFormatException e) { return 0.0; }
    }

    /** Result of the max-move search — package-private for testability. */
    record MoveResult(BigDecimal fromPrice, BigDecimal toPrice,
                      double pctChange, long spanMinutes) {}
}
