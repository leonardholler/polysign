package com.polysign.detector;

import com.polysign.alert.AlertIdFactory;
import com.polysign.alert.AlertService;
import com.polysign.common.AppClock;
import com.polysign.config.CommonDetectorProperties;
import com.polysign.common.CorrelationId;
import com.polysign.model.Alert;
import com.polysign.model.LiquidityTier;
import com.polysign.model.Market;
import com.polysign.model.PriceSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

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

    // Extreme-zone filter cutoffs (tunable via CommonDetectorProperties)
    private final double extremeZoneLow;
    private final double extremeZoneHigh;

    private final MarketLivenessGate livenessGate;

    // ── Diagnostic state ──────────────────────────────────────────────────────
    private record FilterEvent(Instant ts, String reason) {}
    private final ConcurrentLinkedDeque<FilterEvent> filterEvents = new ConcurrentLinkedDeque<>();
    private record SnapshotCountEvent(Instant ts, int count) {}
    private final ConcurrentLinkedDeque<SnapshotCountEvent> snapshotCountEvents = new ConcurrentLinkedDeque<>();
    /** Near-miss: |z| >= threshold * 0.75 but below threshold. Reset each detect() run. */
    private int nearMissInCurrentRun;
    private volatile LastRunStats lastRunStats;

    public record LastRunStats(int checked, int fired, int nearMiss, String runAt) {}

    public record StatDetectorDiagnostics(
            double zScoreTier1, double zScoreTier2, double zScoreTier3,
            int minSnapshots, double minDeltaP,
            int lastRunChecked, int lastRunFired, int lastRunNearMiss, String lastRunAt,
            Map<String, Long> filterCountsLastHour,
            /** Distribution of snapshot counts per market over the last hour (shows warmup progress) */
            Map<String, Long> snapshotCountHistogram) {}

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
            @Value("${polysign.detectors.statistical.min-delta-p:0.03}")           double minDeltaP,
            MarketLivenessGate livenessGate,
            CommonDetectorProperties commonProps) {
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
        this.livenessGate          = livenessGate;
        this.extremeZoneLow        = commonProps.getExtremeZoneLow();
        this.extremeZoneHigh       = commonProps.getExtremeZoneHigh();
    }

    @PostConstruct
    void logConfig() {
        log.info("statistical_anomaly_detector_config z_score_tier1={} z_score_tier2={} z_score_tier3={} min_snapshots={} min_delta_p={} extreme_zone_low={} extreme_zone_high={}",
                zScoreThresholdTier1, zScoreThresholdTier2, zScoreThresholdTier3,
                minSnapshots, minDeltaP, extremeZoneLow, extremeZoneHigh);
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
        nearMissInCurrentRun = 0;

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

        lastRunStats = new LastRunStats(checked, fired, nearMissInCurrentRun, now.toString());
        // Prune filter events and snapshot count observations older than 2 hours
        Instant pruneBefor = now.minus(Duration.ofHours(2));
        while (!filterEvents.isEmpty() && filterEvents.peekFirst().ts().isBefore(pruneBefor)) {
            filterEvents.pollFirst();
        }
        while (!snapshotCountEvents.isEmpty() && snapshotCountEvents.peekFirst().ts().isBefore(pruneBefor)) {
            snapshotCountEvents.pollFirst();
        }

        log.info("statistical_anomaly_detect_complete checked={} fired={}", checked, fired);
    }

    public StatDetectorDiagnostics getDiagnostics(Instant since) {
        Map<String, Long> filterCounts = filterEvents.stream()
                .filter(e -> e.ts().isAfter(since))
                .collect(Collectors.groupingBy(FilterEvent::reason, Collectors.counting()));
        Map<String, Long> snapshotHistogram = snapshotCountEvents.stream()
                .filter(e -> e.ts().isAfter(since))
                .collect(Collectors.groupingBy(
                        e -> snapshotBucket(e.count()),
                        Collectors.counting()));
        LastRunStats stats = lastRunStats;
        return new StatDetectorDiagnostics(
                zScoreThresholdTier1, zScoreThresholdTier2, zScoreThresholdTier3,
                minSnapshots, minDeltaP,
                stats != null ? stats.checked() : 0,
                stats != null ? stats.fired()   : 0,
                stats != null ? stats.nearMiss(): 0,
                stats != null ? stats.runAt()   : null,
                filterCounts,
                snapshotHistogram);
    }

    private static String snapshotBucket(int count) {
        if (count <= 5)  return "0-5";
        if (count <= 10) return "6-10";
        if (count <= 15) return "11-15";
        if (count <= 19) return "16-19";
        if (count <= 29) return "20-29";
        return "30+";
    }

    /**
     * Check a single market for statistical anomaly. Returns true if an alert
     * was created (new), false otherwise.
     *
     * <p>Package-private so unit tests can call it directly with synthetic data.
     */
    boolean checkMarket(Market market, Instant now) {
        // ── Liveness gate: skip ended, paused, or order-frozen markets ────────
        if (!livenessGate.isLive(market)) {
            filterEvents.addLast(new FilterEvent(now, "FILTERED_MARKET_ENDED"));
            return false;
        }

        double volume24h = parseVolume(market.getVolume24h());
        LiquidityTier tier = LiquidityTier.classify(volume24h, tier1MinVolume, tier2MinVolume);

        // Tier-specific z-score threshold — no hard volume floor
        double zScoreThreshold = zScoreThresholdForTier(tier);

        List<PriceSnapshot> snapshots = querySnapshots(market.getMarketId(), now);
        snapshotCountEvents.addLast(new SnapshotCountEvent(now, snapshots.size()));
        if (snapshots.size() < minSnapshots) {
            filterEvents.addLast(new FilterEvent(now, "FILTERED_INSUFFICIENT_SNAPSHOTS"));
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
            filterEvents.addLast(new FilterEvent(now, "FILTERED_ZERO_STDDEV"));
            return false;
        }

        // Z-score of the most recent return
        double lastReturn = returns[returns.length - 1];
        double zScore = (lastReturn - mean) / stddev;
        double absZ = Math.abs(zScore);

        if (absZ < zScoreThreshold) {
            if (absZ >= zScoreThreshold * 0.75) {
                nearMissInCurrentRun++;
                filterEvents.addLast(new FilterEvent(now, "NEAR_MISS_Z_SCORE"));
            } else {
                filterEvents.addLast(new FilterEvent(now, "FILTERED_BELOW_Z_THRESHOLD"));
            }
            return false;
        }

        // Delta-p floor: absolute price change of the last return
        BigDecimal lastPrice = snapshots.get(n - 1).getMidpoint();
        BigDecimal prevPrice = snapshots.get(n - 2).getMidpoint();
        BigDecimal delta = lastPrice.subtract(prevPrice).abs();
        if (delta.compareTo(BigDecimal.valueOf(minDeltaP)) < 0) {
            filterEvents.addLast(new FilterEvent(now, "FILTERED_MIN_DELTA_P"));
            return false;
        }

        // Extreme-zone filter
        double lastD = lastPrice.doubleValue();
        double prevD = prevPrice.doubleValue();
        if ((lastD < extremeZoneLow && prevD < extremeZoneLow) || (lastD > extremeZoneHigh && prevD > extremeZoneHigh)) {
            filterEvents.addLast(new FilterEvent(now, "FILTERED_EXTREME_ZONE"));
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
                    filterEvents.addLast(new FilterEvent(now, "FILTERED_THIN_BOOK"));
                    log.info("alert_suppressed_thin_book marketId={} tier={} spreadBps={} depthAtMid={} reason=spread",
                            market.getMarketId(), tier.label(),
                            String.format("%.2f", snap.spreadBps()),
                            String.format("%.2f", snap.depthAtMid()));
                    return false;
                }
                if (snap.depthAtMid() < minDepthAtMid) {
                    recordSuppression(ALERT_TYPE, tier, "depth");
                    filterEvents.addLast(new FilterEvent(now, "FILTERED_THIN_BOOK"));
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
        String link;
        if (market.getEventSlug() != null) {
            link = "https://polymarket.com/event/" + market.getEventSlug();
        } else {
            String q = market.getQuestion() != null ? market.getQuestion() : market.getMarketId();
            link = "https://polymarket.com/search?query="
                    + URLEncoder.encode(q, StandardCharsets.UTF_8);
        }
        alert.setLink(link);
        alert.setMetadata(metadata);
        alert.setPriceAtAlert(prevPrice);

        boolean created = alertService.tryCreate(alert);
        filterEvents.addLast(new FilterEvent(now, created ? "ALERT_FIRED" : "FILTERED_DEDUPE"));
        return created;
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

}
