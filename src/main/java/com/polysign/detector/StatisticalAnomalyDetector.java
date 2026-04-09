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
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rolling z-score anomaly detector over 1-minute absolute returns.
 *
 * <p>Runs every 60 seconds (after the price movement detector). For each active
 * market with sufficient history (&ge; {@code minSnapshots} snapshots in the last
 * 60 minutes), computes the mean and standard deviation of absolute returns
 * ({@code price[k] - price[k-1]}), then z-scores the most recent return.
 *
 * <p>Fires a {@code statistical_anomaly} alert when:
 * <ul>
 *   <li>z-score &ge; threshold (default 3.0)</li>
 *   <li>24h volume &ge; minVolumeUsdc</li>
 *   <li>absolute price delta &ge; minDeltaP (0.03)</li>
 *   <li>not in extreme zone (both prices &lt; 0.05 or both &gt; 0.95)</li>
 * </ul>
 */
@Component
public class StatisticalAnomalyDetector {

    private static final Logger log = LoggerFactory.getLogger(StatisticalAnomalyDetector.class);
    private static final String ALERT_TYPE = "statistical_anomaly";
    private static final int LOOKBACK_MINUTES = 60;

    private final DynamoDbTable<Market> marketsTable;
    private final DynamoDbTable<PriceSnapshot> snapshotsTable;
    private final AlertService alertService;
    private final OrderbookService orderbookService;
    private final AppClock clock;

    private final double zScoreThreshold;
    private final int minSnapshots;
    private final double minVolumeUsdc;
    private final Duration dedupeWindow;
    private final double minDeltaP;

    public StatisticalAnomalyDetector(
            DynamoDbTable<Market> marketsTable,
            DynamoDbTable<PriceSnapshot> snapshotsTable,
            AlertService alertService,
            OrderbookService orderbookService,
            AppClock clock,
            @Value("${polysign.detectors.statistical.z-score-threshold:3.0}") double zScoreThreshold,
            @Value("${polysign.detectors.statistical.min-snapshots:20}") int minSnapshots,
            @Value("${polysign.detectors.statistical.min-volume-usdc:50000}") double minVolumeUsdc,
            @Value("${polysign.detectors.statistical.dedupe-window-minutes:30}") int dedupeWindowMinutes,
            @Value("${polysign.detectors.statistical.min-delta-p:0.03}") double minDeltaP) {
        this.marketsTable = marketsTable;
        this.snapshotsTable = snapshotsTable;
        this.alertService = alertService;
        this.orderbookService = orderbookService;
        this.clock = clock;
        this.zScoreThreshold = zScoreThreshold;
        this.minSnapshots = minSnapshots;
        this.minVolumeUsdc = minVolumeUsdc;
        this.dedupeWindow = Duration.ofMinutes(dedupeWindowMinutes);
        this.minDeltaP = minDeltaP;
    }

    @Scheduled(fixedDelayString = "${polysign.detectors.statistical.interval-ms:60000}",
               initialDelayString = "${polysign.detectors.statistical.initial-delay-ms:70000}")
    public void run() {
        try (var ignored = CorrelationId.set()) {
            detect();
        } catch (Exception e) {
            log.error("statistical_anomaly_detector_failed", e);
        }
    }

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
                log.warn("statistical_anomaly_check_failed marketId={} error={}",
                        market.getMarketId(), e.getMessage());
            }
        }

        log.info("statistical_anomaly_detect_complete checked={} fired={}", checked, fired);
    }

    /**
     * Check a single market for statistical anomaly. Returns true if an alert
     * was created (new), false otherwise.
     *
     * <p>Package-private so unit tests can call it directly with synthetic data.
     */
    boolean checkMarket(Market market, Instant now) {
        // Volume gate
        double volume24h = parseVolume(market.getVolume24h());
        if (volume24h < minVolumeUsdc) {
            return false;
        }

        List<PriceSnapshot> snapshots = querySnapshots(market.getMarketId(), now);
        if (snapshots.size() < minSnapshots) {
            return false;
        }

        // Compute absolute returns: r[k] = midpoint[k] - midpoint[k-1]
        int n = snapshots.size();
        double[] returns = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            double p0 = snapshots.get(i).getMidpoint().doubleValue();
            double p1 = snapshots.get(i + 1).getMidpoint().doubleValue();
            returns[i] = p1 - p0;
        }

        // Mean and stddev of all returns
        double sum = 0.0;
        for (double r : returns) {
            sum += r;
        }
        double mean = sum / returns.length;

        double sumSqDev = 0.0;
        for (double r : returns) {
            double dev = r - mean;
            sumSqDev += dev * dev;
        }
        double stddev = Math.sqrt(sumSqDev / returns.length);

        // If stddev is zero, we have no volatility information — skip
        if (stddev == 0.0) {
            return false;
        }

        // Z-score of the most recent return
        double lastReturn = returns[returns.length - 1];
        double zScore = (lastReturn - mean) / stddev;
        double absZ = Math.abs(zScore);

        if (absZ < zScoreThreshold) {
            return false;
        }

        // Delta-p floor: absolute price change of the last return
        BigDecimal lastPrice = snapshots.get(n - 1).getMidpoint();
        BigDecimal prevPrice = snapshots.get(n - 2).getMidpoint();
        BigDecimal delta = lastPrice.subtract(prevPrice).abs();
        if (delta.compareTo(BigDecimal.valueOf(minDeltaP)) < 0) {
            return false;
        }

        // Extreme-zone filter
        double lastD = lastPrice.doubleValue();
        double prevD = prevPrice.doubleValue();
        if ((lastD < 0.05 && prevD < 0.05) || (lastD > 0.95 && prevD > 0.95)) {
            return false;
        }

        // Fire alert
        String direction = lastReturn > 0 ? "up" : "down";

        // Orderbook capture — one CLOB call per alert, not per poll.
        Optional<OrderbookService.BookSnapshot> book = captureOrderbook(market.getYesTokenId());

        String alertId = AlertIdFactory.generate(ALERT_TYPE, market.getMarketId(),
                now, dedupeWindow);
        Instant bucketedAt = AlertIdFactory.bucketedInstant(now, dedupeWindow);

        Map<String, String> metadata = new HashMap<>();
        metadata.put("zScore", String.format("%.2f", absZ));
        metadata.put("windowSize", String.valueOf(returns.length));
        metadata.put("mean", String.format("%.6f", mean));
        metadata.put("stddev", String.format("%.6f", stddev));
        metadata.put("lastReturn", String.format("%.6f", lastReturn));
        metadata.put("direction", direction);
        metadata.put("volume24h", String.format("%.0f", volume24h));
        book.ifPresent(b -> {
            metadata.put("spreadBps", String.format("%.2f", b.spreadBps()));
            metadata.put("depthAtMid", String.format("%.2f", b.depthAtMid()));
        });
        metadata.put("detectedAt", now.toString());

        Alert alert = new Alert();
        alert.setAlertId(alertId);
        alert.setCreatedAt(bucketedAt.toString());
        alert.setType(ALERT_TYPE);
        alert.setSeverity("warning");
        alert.setMarketId(market.getMarketId());
        alert.setTitle(String.format("%.1fσ anomaly %s", absZ, direction));
        alert.setDescription(String.format("%s: z-score %.2f (%s) over %d snapshots",
                market.getQuestion() != null ? market.getQuestion() : market.getMarketId(),
                absZ, direction, n));
        alert.setLink("https://polymarket.com/event/" + market.getMarketId());
        alert.setMetadata(metadata);

        return alertService.tryCreate(alert);
    }

    /**
     * Capture orderbook depth. Package-private so tests can override to
     * inject synthetic book data or simulate failures.
     */
    Optional<OrderbookService.BookSnapshot> captureOrderbook(String yesTokenId) {
        try {
            return orderbookService.capture(yesTokenId);
        } catch (Exception e) {
            log.debug("orderbook_capture_error tokenId={} error={}", yesTokenId, e.getMessage());
            return Optional.empty();
        }
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

    private static double parseVolume(String raw) {
        if (raw == null || raw.isBlank()) return 0.0;
        try { return Double.parseDouble(raw); } catch (NumberFormatException e) { return 0.0; }
    }
}
