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
import java.math.MathContext;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

/**
 * Threshold-based price movement detector with liquidity-adjusted thresholds.
 *
 * <p>Runs every 60 seconds. For each active market, queries the last 60 minutes
 * of snapshots and fires a {@code price_movement} alert if price moved
 * &ge; the tier-specific threshold within any {@code windowMinutes}-minute span.
 * No hard volume floor — all markets are in scope. Low-liquidity markets simply
 * require proportionally stronger moves.
 *
 * <h3>Tiers and thresholds</h3>
 * <ul>
 *   <li>Tier 1 (liquid, volume24h &gt; $250k): 8% threshold. No orderbook gate.</li>
 *   <li>Tier 2 (moderate, $50k–$250k): 14% threshold. Orderbook gate required.</li>
 *   <li>Tier 3 (illiquid, &lt; $50k): 20% threshold. Orderbook gate required.</li>
 * </ul>
 *
 * <h3>Orderbook depth gate (Tier 2 and Tier 3 only)</h3>
 * Before firing, calls {@link OrderbookService}. Silently drops the alert if:
 * <ul>
 *   <li>{@code spreadBps > maxSpreadBps} (default 500 bps) — spread too wide</li>
 *   <li>{@code depthAtMid < minDepthAtMid} (default $100 USDC) — book too thin</li>
 * </ul>
 * On orderbook call failure, fires the alert with null book fields rather than
 * suppressing a potential real signal.
 *
 * <p>Dedupe: 30-minute bucketed window via {@link AlertIdFactory}. Bypassed
 * (unbucketed) if the move exceeds 2&times; the tier threshold.
 */
@Component
public class PriceMovementDetector {

    private static final Logger log = LoggerFactory.getLogger(PriceMovementDetector.class);
    private static final String ALERT_TYPE = "price_movement";
    private static final int LOOKBACK_MINUTES = 60;

    private final DynamoDbTable<Market> marketsTable;
    private final DynamoDbTable<PriceSnapshot> snapshotsTable;
    private final DynamoDbTable<Alert> alertsTable;
    private final AlertService alertService;
    private final OrderbookService orderbookService;
    private final AppClock clock;
    private final MeterRegistry meterRegistry;

    // Tier-specific percentage thresholds
    private final double thresholdPctTier1;
    private final double thresholdPctTier2;
    private final double thresholdPctTier3;

    // Tier boundaries
    private final double tier1MinVolume;
    private final double tier2MinVolume;

    // Orderbook depth gate (Tier 2 and Tier 3 only)
    private final double maxSpreadBps;
    private final double minDepthAtMid;

    // Resolution zone config
    private final double resolutionZoneHigh;
    private final double resolutionZoneLow;
    private final double midRangeThresholdMultiplier;
    private final double zoneEntryThresholdDiscount;

    // Per-window volume config
    private final double minWindowVolume;
    private final double highVolumeWindowThreshold;

    private final int windowMinutes;
    private final Duration dedupeWindow;
    private final double minDeltaP;
    private final int maxBypassPerHour;

    // ── Diagnostic state ──────────────────────────────────────────────────────
    private record FilterEvent(Instant ts, String reason) {}
    private final ConcurrentLinkedDeque<FilterEvent> filterEvents = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<DeltaPEvent> deltaPEvents = new ConcurrentLinkedDeque<>();
    private volatile Map<String, MarketDiagnostic> lastMarketDiagnostics = Map.of();
    private volatile int     lastRunChecked;
    private volatile int     lastRunFired;
    private volatile Instant lastRunAt;
    /** Written only in detect() which runs serially via @Scheduled(fixedDelay). */
    private final Map<String, MarketDiagnostic> runDiagBuffer = new HashMap<>();

    /** Per-market state captured during the last detection run. */
    public record MarketDiagnostic(
            String marketId, String question,
            double currentPrice, String zone,
            double effectiveThresh, double recentMovePct,
            String lastDecision, String lastCheckedAt) {}

    /** Full diagnostics snapshot returned by {@link #getDiagnostics(Instant)}. */
    public record PriceDetectorDiagnostics(
            double thresholdTier1Pct, double thresholdTier2Pct, double thresholdTier3Pct,
            double tier1MinVolume, double tier2MinVolume,
            int windowMinutes, double midRangeMultiplier, double zoneEntryDiscount,
            double minWindowVolume, double highVolumeWindowThreshold,
            int lastRunChecked, int lastRunFired, String lastRunAt,
            List<MarketDiagnostic> markets,
            Map<String, Long> filterCountsLastHour,
            /** marketId → max observed delta-p for markets filtered by FILTERED_MIN_DELTA_P in the last hour */
            Map<String, Double> belowMinDeltaP,
            /** Histogram of all observed delta-p values reaching the min-delta-p check in the last hour */
            Map<String, Long> minDeltaPHistogramLastHour) {}

    /** Zone transition classification for the price move. */
    enum ZoneTransition {
        ENTERED_BULLISH, ENTERED_BEARISH, DEEPENING_BULLISH, DEEPENING_BEARISH, MID_RANGE
    }

    /** Tracks observed delta-p values for all markets that reach the min-delta-p check. */
    private record DeltaPEvent(Instant ts, String marketId, double deltaP) {}

    public PriceMovementDetector(
            DynamoDbTable<Market> marketsTable,
            DynamoDbTable<PriceSnapshot> snapshotsTable,
            DynamoDbTable<Alert> alertsTable,
            AlertService alertService,
            OrderbookService orderbookService,
            AppClock clock,
            MeterRegistry meterRegistry,
            @Value("${polysign.detectors.price.threshold-pct-tier1:8.0}")   double thresholdPctTier1,
            @Value("${polysign.detectors.price.threshold-pct-tier2:14.0}")  double thresholdPctTier2,
            @Value("${polysign.detectors.price.threshold-pct-tier3:20.0}")  double thresholdPctTier3,
            @Value("${polysign.detectors.price.window-minutes:15}")          int windowMinutes,
            @Value("${polysign.detectors.liquidity-tiers.tier1-min-volume:250000}") double tier1MinVolume,
            @Value("${polysign.detectors.liquidity-tiers.tier2-min-volume:50000}")  double tier2MinVolume,
            @Value("${polysign.detectors.orderbook-gate.max-spread-bps:500}")       double maxSpreadBps,
            @Value("${polysign.detectors.orderbook-gate.min-depth-at-mid:100.0}")   double minDepthAtMid,
            @Value("${polysign.detectors.price.resolution-zone-high:0.65}")          double resolutionZoneHigh,
            @Value("${polysign.detectors.price.resolution-zone-low:0.35}")           double resolutionZoneLow,
            @Value("${polysign.detectors.price.mid-range-threshold-multiplier:2.0}") double midRangeThresholdMultiplier,
            @Value("${polysign.detectors.price.zone-entry-threshold-discount:0.25}") double zoneEntryThresholdDiscount,
            @Value("${polysign.detectors.price.min-window-volume:5000}")             double minWindowVolume,
            @Value("${polysign.detectors.price.high-volume-window-threshold:20000}") double highVolumeWindowThreshold,
            @Value("${polysign.detectors.price.dedupe-window-minutes:30}")   int dedupeWindowMinutes,
            @Value("${polysign.detectors.price.min-delta-p:0.03}")          double minDeltaP,
            @Value("${polysign.detectors.price.max-bypass-per-hour:3}")     int maxBypassPerHour) {
        this.marketsTable                = marketsTable;
        this.snapshotsTable              = snapshotsTable;
        this.alertsTable                 = alertsTable;
        this.alertService                = alertService;
        this.orderbookService            = orderbookService;
        this.clock                       = clock;
        this.meterRegistry               = meterRegistry;
        this.thresholdPctTier1           = thresholdPctTier1;
        this.thresholdPctTier2           = thresholdPctTier2;
        this.thresholdPctTier3           = thresholdPctTier3;
        this.windowMinutes               = windowMinutes;
        this.tier1MinVolume              = tier1MinVolume;
        this.tier2MinVolume              = tier2MinVolume;
        this.maxSpreadBps                = maxSpreadBps;
        this.minDepthAtMid               = minDepthAtMid;
        this.resolutionZoneHigh          = resolutionZoneHigh;
        this.resolutionZoneLow           = resolutionZoneLow;
        this.midRangeThresholdMultiplier = midRangeThresholdMultiplier;
        this.zoneEntryThresholdDiscount  = zoneEntryThresholdDiscount;
        this.minWindowVolume             = minWindowVolume;
        this.highVolumeWindowThreshold   = highVolumeWindowThreshold;
        this.dedupeWindow                = Duration.ofMinutes(dedupeWindowMinutes);
        this.minDeltaP                   = minDeltaP;
        this.maxBypassPerHour            = maxBypassPerHour;
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
     * Returns a diagnostics snapshot for the last detection run.
     *
     * @param since filter-event look-back cut-off (typically {@code now - 1h})
     */
    public PriceDetectorDiagnostics getDiagnostics(Instant since) {
        Map<String, Long> filterCounts = filterEvents.stream()
                .filter(e -> e.ts().isAfter(since))
                .collect(Collectors.groupingBy(FilterEvent::reason, Collectors.counting()));
        List<MarketDiagnostic> markets = lastMarketDiagnostics.values().stream()
                .sorted(Comparator.comparingDouble(MarketDiagnostic::recentMovePct).reversed())
                .collect(Collectors.toList());

        // Delta-p diagnostics: per-market max observed delta for filtered markets, plus histogram
        Map<String, Double> belowMinDeltaP = deltaPEvents.stream()
                .filter(e -> e.ts().isAfter(since) && e.deltaP() < minDeltaP)
                .collect(Collectors.toMap(
                        DeltaPEvent::marketId,
                        DeltaPEvent::deltaP,
                        Math::max));
        Map<String, Long> deltaPHistogram = deltaPEvents.stream()
                .filter(e -> e.ts().isAfter(since))
                .collect(Collectors.groupingBy(
                        e -> deltaBucket(e.deltaP()),
                        Collectors.counting()));

        return new PriceDetectorDiagnostics(
                thresholdPctTier1, thresholdPctTier2, thresholdPctTier3,
                tier1MinVolume, tier2MinVolume,
                windowMinutes, midRangeThresholdMultiplier, zoneEntryThresholdDiscount,
                minWindowVolume, highVolumeWindowThreshold,
                lastRunChecked, lastRunFired,
                lastRunAt != null ? lastRunAt.toString() : null,
                markets,
                filterCounts,
                belowMinDeltaP,
                deltaPHistogram);
    }

    private static String deltaBucket(double d) {
        if (d < 0.01) return "0.00-0.01";
        if (d < 0.02) return "0.01-0.02";
        if (d < 0.03) return "0.02-0.03";
        if (d < 0.04) return "0.03-0.04";
        return "0.04+";
    }

    /**
     * Core detection loop — public for direct invocation in integration tests.
     */
    public void detect() {
        Instant now = clock.now();
        int checked = 0;
        int fired = 0;
        runDiagBuffer.clear();

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

        // ── Swap diagnostic snapshot ──────────────────────────────────────────
        lastMarketDiagnostics = Map.copyOf(runDiagBuffer);
        lastRunChecked = checked;
        lastRunFired   = fired;
        lastRunAt      = now;
        // Prune filter events and delta-p observations older than 2 hours to bound memory
        Instant pruneBefor = now.minus(Duration.ofHours(2));
        while (!filterEvents.isEmpty() && filterEvents.peekFirst().ts().isBefore(pruneBefor)) {
            filterEvents.pollFirst();
        }
        while (!deltaPEvents.isEmpty() && deltaPEvents.peekFirst().ts().isBefore(pruneBefor)) {
            deltaPEvents.pollFirst();
        }

        log.info("price_movement_detect_complete checked={} fired={}", checked, fired);
    }

    /**
     * Check a single market for price movement. Returns true if an alert was
     * created (new), false otherwise (no movement, filtered, suppressed, or deduplicated).
     *
     * <p>Package-private so unit tests can call it directly with synthetic data
     * without needing a full DynamoDB scan.
     */
    boolean checkMarket(Market market, Instant now) {
        double volume24h = parseVolume(market.getVolume24h());
        LiquidityTier tier = LiquidityTier.classify(volume24h, tier1MinVolume, tier2MinVolume);

        // Tier-specific percentage threshold — no hard volume floor
        double thresholdPct = thresholdForTier(tier);

        // Query snapshots for the last LOOKBACK_MINUTES
        List<PriceSnapshot> snapshots = querySnapshots(market.getMarketId(), now);
        if (snapshots.size() < 2) {
            filterEvents.addLast(new FilterEvent(now, "FILTERED_INSUFFICIENT_SNAPSHOTS"));
            return false;
        }

        // Find the maximum absolute move within any windowMinutes-minute span
        MoveResult move = findMaxMove(snapshots);
        if (move == null) {
            filterEvents.addLast(new FilterEvent(now, "NO_MOVE_FOUND"));
            return false;
        }

        double movePct = move.pctChange;

        // Minimum absolute probability delta: 0.0045→0.0055 is 22% but only 1bp of implied prob
        BigDecimal delta = move.toPrice.subtract(move.fromPrice).abs();
        deltaPEvents.addLast(new DeltaPEvent(now, market.getMarketId(), delta.doubleValue()));
        if (delta.compareTo(BigDecimal.valueOf(minDeltaP)) < 0) {
            filterEvents.addLast(new FilterEvent(now, "FILTERED_MIN_DELTA_P"));
            return false;
        }

        double fromD = move.fromPrice.doubleValue();
        double toD   = move.toPrice.doubleValue();
        String direction = move.toPrice.compareTo(move.fromPrice) >= 0 ? "up" : "down";
        double volumeInWindow = computeVolumeInWindow(snapshots);

        // Diagnostic state — populated as we pass through each filter stage below.
        ZoneTransition zoneTransition    = ZoneTransition.MID_RANGE; // refined below
        double effectiveThresholdPct     = thresholdPct;             // refined below
        String filterReason              = null;
        boolean fired                    = false;

        // ── Resolved market filter ────────────────────────────────────────────
        // Skip markets that have effectively resolved (destination at terminal price).
        // A market at 2¢ or 98¢ has no actionable edge; alert would be noise.
        if (toD < 0.02 || toD > 0.98) {
            filterReason = "FILTERED_RESOLVED";

        // ── Extreme-zone filter ───────────────────────────────────────────────
        // Tail markets produce huge pct moves from tiny absolute moves.
        } else if ((fromD < 0.05 && toD < 0.05) || (fromD > 0.95 && toD > 0.95)) {
            filterReason = "FILTERED_EXTREME_ZONE";

        } else {
            zoneTransition = computeZoneTransition(fromD, toD, direction);

            // ── Per-window volume check ───────────────────────────────────────
            // If snapshots carry static volume24h (same value throughout), volumeInWindow == -1.0.
            // TODO: capture per-snapshot volume deltas to enable per-window volume filtering
            if (volumeInWindow >= 0.0 && volumeInWindow < minWindowVolume) {
                filterReason = "FILTERED_LOW_VOLUME"; // noise: move happened on negligible volume

            } else {
                // ── Effective threshold computation ───────────────────────────
                // Priority order: zone-entry discount < mid-range multiplier (mutually exclusive).
                effectiveThresholdPct = thresholdPct;
                if (zoneTransition == ZoneTransition.MID_RANGE) {
                    effectiveThresholdPct *= midRangeThresholdMultiplier;
                } else if (zoneTransition == ZoneTransition.ENTERED_BULLISH
                        || zoneTransition == ZoneTransition.ENTERED_BEARISH) {
                    effectiveThresholdPct *= (1.0 - zoneEntryThresholdDiscount);
                }
                // Additional discount for heavy-volume resolution-zone moves
                boolean inResolutionZone = (zoneTransition != ZoneTransition.MID_RANGE);
                if (inResolutionZone) {
                    if (volumeInWindow >= highVolumeWindowThreshold) {
                        effectiveThresholdPct *= (1.0 - zoneEntryThresholdDiscount);
                    } else if (volumeInWindow < 0.0 && volume24h > tier1MinVolume) {
                        effectiveThresholdPct *= (1.0 - zoneEntryThresholdDiscount);
                    }
                }

                if (movePct < effectiveThresholdPct) {
                    filterReason = "FILTERED_BELOW_THRESHOLD";

                } else if (!hasMomentum(snapshots, direction)) {
                    filterReason = "FILTERED_NO_MOMENTUM";

                } else {
                    // ── Orderbook depth gate (Tier 2 and Tier 3 only) ─────────
                    boolean watched    = Boolean.TRUE.equals(market.getIsWatched());
                    String severity    = watched ? "critical" : "warning";
                    boolean bypassDedupe = movePct >= thresholdPct * 2
                            && isActivelyMoving(snapshots, now);

                    Optional<OrderbookService.BookSnapshot> book;
                    if (tier == LiquidityTier.TIER_1) {
                        // Capture for metadata, but gate does not apply
                        book = captureOrderbook(market.getYesTokenId());
                    } else {
                        // Gate applies: check book quality before deciding to fire
                        book = captureOrderbook(market.getYesTokenId());
                        if (book.isPresent()) {
                            OrderbookService.BookSnapshot snap = book.get();
                            if (snap.spreadBps() > maxSpreadBps) {
                                recordSuppression(ALERT_TYPE, tier, "spread");
                                log.info("alert_suppressed_thin_book marketId={} tier={} spreadBps={} depthAtMid={} reason=spread",
                                        market.getMarketId(), tier.label(),
                                        String.format("%.2f", snap.spreadBps()),
                                        String.format("%.2f", snap.depthAtMid()));
                                filterReason = "FILTERED_THIN_BOOK";
                            } else if (snap.depthAtMid() < minDepthAtMid) {
                                recordSuppression(ALERT_TYPE, tier, "depth");
                                log.info("alert_suppressed_thin_book marketId={} tier={} spreadBps={} depthAtMid={} reason=depth",
                                        market.getMarketId(), tier.label(),
                                        String.format("%.2f", snap.spreadBps()),
                                        String.format("%.2f", snap.depthAtMid()));
                                filterReason = "FILTERED_THIN_BOOK";
                            }
                        }
                        // book.isEmpty() means the call failed — fire anyway (per spec)
                    }

                    if (filterReason == null) {
                        // ── Per-market bypass rate cap ─────────────────────────
                        if (bypassDedupe) {
                            int recentCount = countRecentBypassAlerts(market.getMarketId(), now);
                            if (recentCount >= maxBypassPerHour) {
                                log.info("alert_rate_capped marketId={} count={}",
                                        market.getMarketId(), recentCount);
                                filterReason = "FILTERED_RATE_CAPPED";
                            }
                        }

                        if (filterReason == null) {
                            // ── Fire the alert ────────────────────────────────
                            // Always use the 30-minute dedupe window so that two polls detecting
                            // the same directional move within the same bucket produce the same
                            // alertId and the DynamoDB attribute_not_exists condition deduplicates
                            // them. Previously bypassDedupe used Duration.ZERO which gave per-second
                            // IDs and caused the same move to fire 2-3x.
                            Duration effectiveWindow = dedupeWindow;
                            // Direction is included in the hash to distinguish UP vs DOWN moves
                            // within the same time bucket for the same market.
                            String alertId = AlertIdFactory.generate(ALERT_TYPE,
                                    market.getMarketId(), now, effectiveWindow, direction);
                            Instant bucketedAt = AlertIdFactory.bucketedInstant(now, effectiveWindow);

                            Map<String, String> metadata = new HashMap<>();
                            metadata.put("movePct",        String.format("%.2f", movePct));
                            metadata.put("fromPrice",      move.fromPrice.toPlainString());
                            metadata.put("toPrice",        move.toPrice.toPlainString());
                            metadata.put("direction",      direction);
                            metadata.put("zoneTransition", zoneTransition.name());
                            metadata.put("spanMinutes",    String.valueOf(move.spanMinutes));
                            metadata.put("volume24h",      String.format("%.0f", volume24h));
                            metadata.put("liquidityTier",  tier.label());
                            metadata.put("isWatched",      String.valueOf(watched));
                            metadata.put("bypassedDedupe", String.valueOf(bypassDedupe));
                            if (book.isPresent()) {
                                metadata.put("spreadBps",  String.format("%.2f", book.get().spreadBps()));
                                metadata.put("depthAtMid", String.format("%.2f", book.get().depthAtMid()));
                            }
                            metadata.put("detectedAt", now.toString());

                            Alert alert = new Alert();
                            alert.setAlertId(alertId);
                            alert.setCreatedAt(bucketedAt.toString());
                            alert.setType(ALERT_TYPE);
                            alert.setSeverity(severity);
                            alert.setMarketId(market.getMarketId());
                            alert.setTitle(String.format("%.1f%% price move %s", movePct, direction));
                            alert.setDescription(String.format(
                                    "%s moved %.1f%% %s in %d min (%.4f → %.4f)",
                                    market.getQuestion() != null
                                            ? market.getQuestion() : market.getMarketId(),
                                    movePct, direction, move.spanMinutes,
                                    move.fromPrice, move.toPrice));
                            String link;
                            if (market.getEventSlug() != null) {
                                link = "https://polymarket.com/event/" + market.getEventSlug();
                            } else {
                                String q = market.getQuestion() != null
                                        ? market.getQuestion() : market.getMarketId();
                                link = "https://polymarket.com/search?query="
                                        + URLEncoder.encode(q, StandardCharsets.UTF_8);
                            }
                            alert.setLink(link);
                            alert.setMetadata(metadata);

                            fired = alertService.tryCreate(alert);
                            filterReason = fired ? "ALERT_FIRED" : "FILTERED_DEDUPE";
                        }
                    }
                }
            }
        }

        // ── Diagnostic log + state recording ─────────────────────────────────
        String finalDecision = filterReason != null ? filterReason : "ALERT_FIRED";
        filterEvents.addLast(new FilterEvent(now, finalDecision));
        if (movePct >= minDeltaP * 100) {
            log.debug("price_check marketId={} question={} from={} to={} movePct={}% zone={} momentum={} volWindow={} effectiveThresh={}% result={}",
                    market.getMarketId(),
                    market.getQuestion() != null
                            ? market.getQuestion().substring(0, Math.min(50, market.getQuestion().length()))
                            : "?",
                    String.format("%.3f", fromD),
                    String.format("%.3f", toD),
                    String.format("%.1f", movePct),
                    zoneTransition,
                    hasMomentum(snapshots, direction),
                    String.format("%.0f", volumeInWindow),
                    String.format("%.1f", effectiveThresholdPct),
                    finalDecision);
            runDiagBuffer.put(market.getMarketId(), new MarketDiagnostic(
                    market.getMarketId(),
                    market.getQuestion(),
                    toD,
                    zoneTransition.name(),
                    effectiveThresholdPct,
                    movePct,
                    finalDecision,
                    now.toString()));
        }

        return fired;
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

    /**
     * Returns true if the price is actively still moving — i.e., the delta between
     * the most recent snapshot and a snapshot ~5 minutes ago is at least minDeltaP.
     *
     * <p>Used to guard bypass-dedupe mode: a large historical move with a now-settled
     * price should not continuously re-fire on every 60-second cycle.
     *
     * <p>Package-private for test access.
     */
    boolean isActivelyMoving(List<PriceSnapshot> snapshots, Instant now) {
        if (snapshots.isEmpty()) return false;

        // Most recent snapshot
        PriceSnapshot latest = snapshots.get(snapshots.size() - 1);
        BigDecimal latestPrice = latest.getMidpoint();
        if (latestPrice == null) return false;

        // Look for a snapshot from roughly 5 minutes ago (within 2–8 min window)
        Instant recentCutoff   = now.minus(Duration.ofMinutes(8));
        Instant recentFloor    = now.minus(Duration.ofMinutes(2));

        BigDecimal olderPrice = null;
        for (int i = snapshots.size() - 2; i >= 0; i--) {
            PriceSnapshot snap = snapshots.get(i);
            if (snap.getMidpoint() == null || snap.getTimestamp() == null) continue;
            Instant ts = Instant.parse(snap.getTimestamp());
            if (ts.isBefore(recentCutoff)) break; // too old
            if (ts.isBefore(recentFloor)) {
                olderPrice = snap.getMidpoint();
                break;
            }
        }

        if (olderPrice == null) {
            // Not enough recent history to judge — assume settling to be conservative
            return false;
        }

        BigDecimal recentDelta = latestPrice.subtract(olderPrice).abs();
        return recentDelta.compareTo(BigDecimal.valueOf(minDeltaP)) >= 0;
    }

    /**
     * Classify the zone transition for a price move from {@code fromD} to {@code toD}.
     *
     * <ul>
     *   <li>ENTERED_BULLISH — crossed from mid-range into &gt;resolutionZoneHigh</li>
     *   <li>ENTERED_BEARISH — crossed from mid-range into &lt;resolutionZoneLow</li>
     *   <li>DEEPENING_BULLISH — was already above high and moved higher</li>
     *   <li>DEEPENING_BEARISH — was already below low and moved lower</li>
     *   <li>MID_RANGE — both prices in the 35–65 band, or reversal from resolution zone</li>
     * </ul>
     *
     * Package-private for test access.
     */
    ZoneTransition computeZoneTransition(double fromD, double toD, String direction) {
        boolean toIsHigh = toD > resolutionZoneHigh;
        boolean toIsLow  = toD < resolutionZoneLow;

        if (toIsHigh && "up".equals(direction)) {
            // Moving into or deeper into the bullish zone
            return fromD <= resolutionZoneHigh
                    ? ZoneTransition.ENTERED_BULLISH
                    : ZoneTransition.DEEPENING_BULLISH;
        }
        if (toIsLow && "down".equals(direction)) {
            // Moving into or deeper into the bearish zone
            return fromD >= resolutionZoneLow
                    ? ZoneTransition.ENTERED_BEARISH
                    : ZoneTransition.DEEPENING_BEARISH;
        }
        // Everything else: mid-range move, or reversal away from a resolution zone
        return ZoneTransition.MID_RANGE;
    }

    /**
     * Approximate volume traded during the detection window by computing the delta
     * between the latest and earliest snapshot's {@code volume24h} field.
     *
     * <p>Returns the positive delta if the volume24h values differ (indicating the
     * market's rolling 24h volume changed during the window). Returns {@code -1.0}
     * as a sentinel when the values are identical or null, indicating that per-window
     * volume data is not available and callers should fall back to market-level volume.
     *
     * <p>Package-private for test access.
     */
    double computeVolumeInWindow(List<PriceSnapshot> snapshots) {
        if (snapshots.size() < 2) return -1.0;
        BigDecimal earliest = snapshots.get(0).getVolume24h();
        BigDecimal latest   = snapshots.get(snapshots.size() - 1).getVolume24h();
        if (earliest == null || latest == null) return -1.0;
        if (earliest.compareTo(latest) == 0) return -1.0; // static — no per-window data
        double delta = latest.subtract(earliest).doubleValue();
        return delta >= 0 ? delta : -1.0; // guard against clock skew or stale data
    }

    /**
     * Check that the most recent snapshot-to-snapshot move is in {@code direction}.
     * If fewer than 3 snapshots are available, returns true (no penalization).
     * Null midpoints are treated as non-moving (conservative).
     *
     * <p>Requires only the latest move to be in the correct direction. This filters
     * stale/reversed signals (latest snapshot moved against direction) but allows
     * intermediate jitter (one flat or reversed step in the middle).
     *
     * <p>Package-private for test access.
     */
    boolean hasMomentum(List<PriceSnapshot> snapshots, String direction) {
        int n = snapshots.size();
        if (n < 3) return true;

        PriceSnapshot s1 = snapshots.get(n - 2);
        PriceSnapshot s2 = snapshots.get(n - 1); // most recent

        if (s1.getMidpoint() == null || s2.getMidpoint() == null) {
            return true; // missing data: don't penalize
        }

        boolean up = "up".equals(direction);

        // Only require the latest move to be in the right direction.
        // This filters stale/reversed signals but allows intermediate jitter.
        return up
                ? s2.getMidpoint().compareTo(s1.getMidpoint()) > 0
                : s2.getMidpoint().compareTo(s1.getMidpoint()) < 0;
    }

    /**
     * Count price_movement alerts fired for this market in the last 60 minutes,
     * using the marketId-createdAt-index GSI. Returns 0 if the table is unavailable
     * (e.g. in unit-test contexts where alertsTable is null).
     *
     * <p>Package-private so tests can override to inject a fixed count.
     */
    int countRecentBypassAlerts(String marketId, Instant now) {
        if (alertsTable == null) return 0;
        String from = now.minus(Duration.ofHours(1)).toString();
        String to   = now.toString();
        var qc = QueryConditional.sortBetween(
                Key.builder().partitionValue(marketId).sortValue(from).build(),
                Key.builder().partitionValue(marketId).sortValue(to).build());
        return (int) alertsTable.index("marketId-createdAt-index")
                .query(r -> r.queryConditional(qc))
                .stream()
                .flatMap(p -> p.items().stream())
                .filter(a -> ALERT_TYPE.equals(a.getType()))
                .count();
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

    private double thresholdForTier(LiquidityTier tier) {
        return switch (tier) {
            case TIER_1 -> thresholdPctTier1;
            case TIER_2 -> thresholdPctTier2;
            case TIER_3 -> thresholdPctTier3;
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
