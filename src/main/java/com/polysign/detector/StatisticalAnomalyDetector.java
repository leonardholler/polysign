package com.polysign.detector;

import com.polysign.alert.AlertIdFactory;
import com.polysign.alert.AlertService;
import com.polysign.common.AppClock;
import com.polysign.common.CorrelationId;
import com.polysign.model.Alert;
import com.polysign.model.LiquidityTier;
import com.polysign.model.Market;
import com.polysign.model.PriceSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
 * Rolling z-score anomaly detector with liquidity-adjusted thresholds.
 *
 * <p>Runs every 60 seconds (after the price movement detector). For each active
 * market with sufficient history (&ge; {@code minSnapshots} snapshots in the last
 * 60 minutes), computes the mean and standard deviation of absolute returns
 * ({@code price[k] - price[k-1]}), then z-scores the most recent return.
 *
 * <h3>Tiers and z-score thresholds</h3>
 * <ul>
 *   <li>Tier 1 (liquid, volume24h &gt; $250k): z ≥ 3.0. No orderbook gate.</li>
 *   <li>Tier 2 (moderate, $50k–$250k): z ≥ 4.0. Orderbook gate required.</li>
 *   <li>Tier 3 (illiquid, &lt; $50k): z ≥ 5.0. Orderbook gate required.</li>
 * </ul>
 *
 * <p>No hard volume floor — all markets are in scope. Low-liquidity markets
 * simply require a stronger anomaly signal to fire.
 *
 * <h3>Orderbook depth gate (Tier 2 and Tier 3 only)</h3>
 * Before firing, calls {@link OrderbookService}. Silently drops the alert if:
 * <ul>
 *   <li>{@code spreadBps > maxSpreadBps} (default 500 bps)</li>
 *   <li>{@code depthAtMid < minDepthAtMid} (default $100 USDC)</li>
 * </ul>
 * On orderbook call failure, fires the alert with null book fields.
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
    private final MeterRegistry meterRegistry;

    // Tier-specific z-score thresholds
    private final double zScoreThresholdTier1;
    private final double zScoreThresholdTier2;
    private final double zScoreThresholdTier3;

    // Tier boundaries (shared with PriceMovementDetector via same YAML keys)
    private final double tier1MinVolume;
    private final double tier2MinVolume;

    // Orderbook depth gate
    private final double maxSpreadBps;
    private final double minDepthAtMid;

    private final int minSnapshots;
    private final Duration dedupeWindow;
    private final double minDeltaP;

    public StatisticalAnomalyDetector(
            DynamoDbTable<Market> marketsTable,
            DynamoDbTable<PriceSnapshot> snapshotsTable,
            AlertService alertService,
            OrderbookService orderbookService,
            AppClock clock,
            MeterRegistry meterRegistry,
            @Value("${polysign.detectors.statistical.z-score-threshold-tier1:3.0}") double zScoreThresholdTier1,
            @Value("${polysign.detectors.statistical.z-score-threshold-tier2:4.0}") double zScoreThresholdTier2,
            @Value("${polysign.detectors.statistical.z-score-threshold-tier3:5.0}") double zScoreThresholdTier3,
            @Value("${polysign.detectors.statistical.min-snapshots:20}")            int minSnapshots,
            @Value("${polysign.detectors.liquidity-tiers.tier1-min-volume:250000}") double tier1MinVolume,
            @Value("${polysign.detectors.liquidity-tiers.tier2-min-volume:50000}")  double tier2MinVolume,
            @Value("${polysign.detectors.orderbook-gate.max-spread-bps:500}")       double maxSpreadBps,
            @Value("${polysign.detectors.orderbook-gate.min-depth-at-mid:100.0}")   double minDepthAtMid,
            @Value("${polysign.detectors.statistical.dedupe-window-minutes:30}")    int dedupeWindowMinutes,
            @Value("${polysign.detectors.statistical.min-delta-p:0.03}")           double minDeltaP) {
        this.marketsTable          = marketsTable;
        this.snapshotsTable        = snapshotsTable;
        this.alertService          = alertService;
        this.orderbookService      = orderbookService;
        this.clock                 = clock;
        this.meterRegistry         = meterRegistry;
        this.zScoreThresholdTier1  = zScoreThresholdTier1;
        this.zScoreThresholdTier2  = zScoreThresholdTier2;
        this.zScoreThresholdTier3  = zScoreThresholdTier3;
        this.minSnapshots          = minSnapshots;
        this.tier1MinVolume        = tier1MinVolume;
        this.tier2MinVolume        = tier2MinVolume;
        this.maxSpreadBps          = maxSpreadBps;
        this.minDepthAtMid         = minDepthAtMid;
        this.dedupeWindow          = Duration.ofMinutes(dedupeWindowMinutes);
        this.minDeltaP             = minDeltaP;
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
        double volume24h = parseVolume(market.getVolume24h());
        LiquidityTier tier = LiquidityTier.classify(volume24h, tier1MinVolume, tier2MinVolume);

        // Tier-specific z-score threshold — no hard volume floor
        double zScoreThreshold = zScoreThresholdForTier(tier);

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

        // ── Orderbook depth gate (Tier 2 and Tier 3 only) ─────────────────────
        String direction = lastReturn > 0 ? "up" : "down";
        Optional<OrderbookService.BookSnapshot> book;
        if (tier == LiquidityTier.TIER_1) {
            book = captureOrderbook(market.getYesTokenId());
        } else {
            book = captureOrderbook(market.getYesTokenId());
            if (book.isPresent()) {
                OrderbookService.BookSnapshot snap = book.get();
                if (snap.spreadBps() > maxSpreadBps) {
                    recordSuppression(ALERT_TYPE, tier, "spread");
                    log.info("alert_suppressed_thin_book marketId={} tier={} spreadBps={} depthAtMid={} reason=spread",
                            market.getMarketId(), tier.label(),
                            String.format("%.2f", snap.spreadBps()),
                            String.format("%.2f", snap.depthAtMid()));
                    return false;
                }
                if (snap.depthAtMid() < minDepthAtMid) {
                    recordSuppression(ALERT_TYPE, tier, "depth");
                    log.info("alert_suppressed_thin_book marketId={} tier={} spreadBps={} depthAtMid={} reason=depth",
                            market.getMarketId(), tier.label(),
                            String.format("%.2f", snap.spreadBps()),
                            String.format("%.2f", snap.depthAtMid()));
                    return false;
                }
            }
            // book.isEmpty() → call failed → fire anyway
        }

        // ── Fire the alert ────────────────────────────────────────────────────
        // Include direction in the hash so that a bullish anomaly and a bearish anomaly
        // within the same 30-minute bucket for the same market produce distinct alert IDs.
        String alertId = AlertIdFactory.generate(ALERT_TYPE, market.getMarketId(),
                now, dedupeWindow, direction);
        Instant bucketedAt = AlertIdFactory.bucketedInstant(now, dedupeWindow);

        Map<String, String> metadata = new HashMap<>();
        metadata.put("zScore",        String.format("%.2f", absZ));
        metadata.put("windowSize",    String.valueOf(returns.length));
        metadata.put("mean",          String.format("%.6f", mean));
        metadata.put("stddev",        String.format("%.6f", stddev));
        metadata.put("lastReturn",    String.format("%.6f", lastReturn));
        metadata.put("direction",     direction);
        metadata.put("volume24h",     String.format("%.0f", volume24h));
        metadata.put("liquidityTier", tier.label());
        book.ifPresent(b -> {
            metadata.put("spreadBps",  String.format("%.2f", b.spreadBps()));
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
        String linkSlug = market.getSlug() != null ? market.getSlug() : market.getMarketId();
        alert.setLink("https://polymarket.com/event/" + cleanSlug(linkSlug));
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

    private double zScoreThresholdForTier(LiquidityTier tier) {
        return switch (tier) {
            case TIER_1 -> zScoreThresholdTier1;
            case TIER_2 -> zScoreThresholdTier2;
            case TIER_3 -> zScoreThresholdTier3;
        };
    }

    private void recordSuppression(String detectorType, LiquidityTier tier, String reason) {
        Counter.builder("polysign.alerts.suppressed")
                .tag("type",   detectorType)
                .tag("tier",   tier.label())
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }

    private static double parseVolume(String raw) {
        if (raw == null || raw.isBlank()) return 0.0;
        try { return Double.parseDouble(raw); } catch (NumberFormatException e) { return 0.0; }
    }

    /** Strips trailing numeric outcome IDs from a Polymarket slug to produce an event-level URL. */
    static String cleanSlug(String slug) {
        if (slug == null) return slug;
        String s = slug;
        while (true) {
            int last = s.lastIndexOf('-');
            if (last < 0) break;
            String tail = s.substring(last + 1);
            if (!tail.matches("\\d+")) break;
            if (tail.length() < 3) break;
            if (tail.length() == 4 && tail.compareTo("2020") >= 0 && tail.compareTo("2030") <= 0) break;
            s = s.substring(0, last);
        }
        return s;
    }
}
