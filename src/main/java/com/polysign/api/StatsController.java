package com.polysign.api;

import com.polysign.backtest.SignalPerformanceService;
import com.polysign.backtest.SignalPerformanceService.AggregatePrecision;
import com.polysign.backtest.SignalPerformanceService.AggregateSkill;
import com.polysign.common.AppClock;
import com.polysign.common.AppStats;
import com.polysign.model.AlertOutcome;
import com.polysign.model.WatchedWallet;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight stats endpoint for the dashboard header bar.
 *
 * GET /api/stats returns:
 * <ul>
 *   <li>marketsTracked         — Micrometer gauge set by MarketPoller</li>
 *   <li>alertsFiredToday       — in-memory counter in AppStats, incremented by AlertService</li>
 *   <li>insiderSignatureCount  — same, filtered to type=insider_signature</li>
 *   <li>marketsInResolutionZone — in-memory counter set by MarketPoller each cycle</li>
 *   <li>watchedWallets         — watched_wallets table count (small, ≤~100 rows)</li>
 *   <li>walletsSeenToday       — in-memory counter maintained by WalletPoller</li>
 *   <li>lastPollTime           — last MarketPoller cycle completion time (ISO-8601)</li>
 *   <li>ntfyTopic              — the configured ntfy.sh topic name</li>
 * </ul>
 *
 * <p>The full response is cached for {@value #CACHE_TTL_MS} ms.
 */
@RestController
@RequestMapping({"/api/stats", "/api/dashboard/stats"})
public class StatsController {

    /** Response cache TTL. Dashboard polls every 10 s but stats don't need sub-minute freshness. */
    private static final long CACHE_TTL_MS = 60_000L;

    private final DynamoDbTable<WatchedWallet> watchedWalletsTable;
    private final DynamoDbTable<AlertOutcome>  alertOutcomesTable;
    private final MeterRegistry                meterRegistry;
    private final AppStats                     appStats;
    private final AppClock                     clock;
    private final String                       ntfyTopic;
    private final SignalPerformanceService     signalPerformanceService;

    private final AtomicReference<StatsResponse> cachedStats    = new AtomicReference<>();
    private volatile long                        cachedAtMillis = 0L;

    public StatsController(
            DynamoDbTable<WatchedWallet> watchedWalletsTable,
            DynamoDbTable<AlertOutcome> alertOutcomesTable,
            MeterRegistry meterRegistry,
            AppStats appStats,
            AppClock clock,
            @Value("${polysign.ntfy.topic}") String ntfyTopic,
            SignalPerformanceService signalPerformanceService) {
        this.watchedWalletsTable       = watchedWalletsTable;
        this.alertOutcomesTable        = alertOutcomesTable;
        this.meterRegistry             = meterRegistry;
        this.appStats                  = appStats;
        this.clock                     = clock;
        this.ntfyTopic                 = ntfyTopic;
        this.signalPerformanceService  = signalPerformanceService;
    }

    public record StatsResponse(
            long marketsTracked,
            long alertsFiredToday,
            long watchedWallets,
            String lastPollTime,
            String ntfyTopic,
            Double signalPrecision7d1h,
            Double signalPrecision7d15m,
            long scoredSamples7d1h,
            long scoredSamples7d15m,
            long marketsInResolutionZone,
            long alertsInResolutionZone,
            long resolutionCorrect,
            Double resolutionAccuracyPct,
            long walletsSeenToday,
            long insiderSignatureCount,
            Double insiderSignaturePrecision7d1h,
            long insiderSignatureSamples7d1h,
            /** Mean Brier skill (resolution horizon, core zone). Positive = beat market. */
            Double meanBrierSkillResolutionCore,
            /** Number of scorable, non-dead-zone resolution outcomes contributing to skill. */
            int brierCountResolutionCore,
            /** Total scorable resolution outcomes (core + dead-zone). */
            int scorableCountResolution) {}

    @GetMapping
    public StatsResponse getStats() {
        long nowMs = clock.now().toEpochMilli();
        StatsResponse cached = cachedStats.get();
        if (cached != null && nowMs - cachedAtMillis < CACHE_TTL_MS) {
            return cached;
        }

        // marketsTracked from the Micrometer gauge registered by MarketPoller
        Gauge trackedGauge = meterRegistry.find("polysign.markets.tracked").gauge();
        long marketsTracked = trackedGauge != null ? (long) trackedGauge.value() : 0L;

        // alertsFiredToday and insiderSignatureCount are maintained by AlertService
        // via AppStats.recordAlertFired() — no table scan needed.
        long alertsFiredToday    = appStats.getAlertsFiredToday();
        long insiderSignatureCount = appStats.getInsiderSignatureCount();

        // watchedWallets: count the watched_wallets table (small — max ~100 entries)
        long watchedWallets = watchedWalletsTable.scan().items().stream().count();

        long walletsSeenToday = appStats.walletsSeenInLast24h(clock.getClock());

        // lastPollTime from AppStats (set by MarketPoller after each successful cycle)
        Instant lastPoll = appStats.getLastMarketPollAt();

        // signal precision over last 7 days
        Instant since7d = clock.now().minus(Duration.ofDays(7));
        AggregatePrecision ap1h  = signalPerformanceService.getAggregatePrecision("t1h",  since7d);
        AggregatePrecision ap15m = signalPerformanceService.getAggregatePrecision("t15m", since7d);

        // insiderSignature precision (7d, t1h)
        var insiderPerf = signalPerformanceService.getPerformance("insider_signature", "t1h", since7d);
        Double insiderPrec = insiderPerf.detectors().isEmpty()
                ? null : insiderPerf.detectors().get(0).precision();
        long insiderSamples = insiderPerf.detectors().isEmpty()
                ? 0 : insiderPerf.detectors().get(0).count();

        // Brier skill for top stat card (resolution horizon, core zone, all-time)
        AggregateSkill resSkill = signalPerformanceService.getAggregateSkill("resolution", null);

        // marketsInResolutionZone is maintained by MarketPoller after each cycle — no scan needed.
        long marketsInResolutionZone = appStats.getMarketsInResolutionZone();

        // TODO: add a firedAt GSI to alert_outcomes so this can be a time-bounded query
        // instead of a bounded scan over the first 1000 items by hash-key order.
        List<AlertOutcome> resolutionOutcomes = alertOutcomesTable
                .scan(ScanEnhancedRequest.builder().limit(1000).build()).items().stream()
                .filter(o -> "resolution".equals(o.getHorizon()))
                .toList();

        long alertsInResolutionZone = resolutionOutcomes.size();

        // Correct: direction match AND price fully resolved (1.0 or 0.0).
        long resolutionCorrect = resolutionOutcomes.stream()
                .filter(o -> o.getDirectionPredicted() != null
                        && o.getDirectionPredicted().equals(o.getDirectionRealized())
                        && isFullyResolved(o.getPriceAtHorizon()))
                .count();

        Double resolutionAccuracyPct = alertsInResolutionZone == 0 ? null
                : Math.round((double) resolutionCorrect / alertsInResolutionZone * 1000.0) / 10.0;

        StatsResponse response = new StatsResponse(
                marketsTracked,
                alertsFiredToday,
                watchedWallets,
                lastPoll != null ? lastPoll.toString() : null,
                ntfyTopic,
                ap1h.precision(),
                ap15m.precision(),
                ap1h.scoredSamples(),
                ap15m.scoredSamples(),
                marketsInResolutionZone,
                alertsInResolutionZone,
                resolutionCorrect,
                resolutionAccuracyPct,
                walletsSeenToday,
                insiderSignatureCount,
                insiderPrec,
                insiderSamples,
                resSkill.meanBrierSkillCoreZone(),
                resSkill.brierCountCoreZone(),
                resSkill.scorableCount());

        cachedAtMillis = nowMs;
        cachedStats.set(response);
        return response;
    }

    /** Returns true when priceAtHorizon is a decisive binary outcome (>= 0.99 or <= 0.01). */
    private static boolean isFullyResolved(BigDecimal price) {
        if (price == null) return false;
        double v = price.doubleValue();
        return v >= 0.99 || v <= 0.01;
    }
}
