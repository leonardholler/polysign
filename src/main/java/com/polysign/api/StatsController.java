package com.polysign.api;

import com.polysign.backtest.MarketPredicates;
import com.polysign.backtest.SignalPerformanceService;
import com.polysign.backtest.SignalPerformanceService.AggregatePrecision;
import com.polysign.common.AppClock;
import com.polysign.common.AppStats;
import com.polysign.model.Alert;
import com.polysign.model.AlertOutcome;
import com.polysign.model.Market;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight stats endpoint for the dashboard header bar.
 *
 * GET /api/stats returns:
 * <ul>
 *   <li>marketsTracked      — from the Micrometer gauge set by MarketPoller</li>
 *   <li>alertsFiredToday    — single alerts-table scan filtered to today UTC</li>
 *   <li>watchedWallets      — watched_wallets table count</li>
 *   <li>walletsSeenToday    — in-memory counter maintained by WalletPoller (Option B)</li>
 *   <li>insiderSignatureCount — derived from the same alerts scan</li>
 *   <li>lastPollTime        — last MarketPoller cycle completion time (ISO-8601)</li>
 *   <li>ntfyTopic           — the configured ntfy.sh topic name</li>
 * </ul>
 *
 * <p>The full response is cached for {@value #CACHE_TTL_MS} ms. The dashboard polls
 * every ~10 s; the cache cuts DynamoDB load ~10× under any concurrent request burst.
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    /** Response cache TTL — matches the dashboard poll interval. */
    private static final long CACHE_TTL_MS = 10_000L;

    private final DynamoDbTable<Alert>         alertsTable;
    private final DynamoDbTable<WatchedWallet> watchedWalletsTable;
    private final DynamoDbTable<Market>        marketsTable;
    private final DynamoDbTable<AlertOutcome>  alertOutcomesTable;
    private final MeterRegistry                meterRegistry;
    private final AppStats                     appStats;
    private final AppClock                     clock;
    private final String                       ntfyTopic;
    private final SignalPerformanceService     signalPerformanceService;

    /** Fix 3: 10-second in-process cache. */
    private final AtomicReference<StatsResponse> cachedStats    = new AtomicReference<>();
    private volatile long                        cachedAtMillis = 0L;

    public StatsController(
            DynamoDbTable<Alert> alertsTable,
            DynamoDbTable<WatchedWallet> watchedWalletsTable,
            DynamoDbTable<Market> marketsTable,
            DynamoDbTable<AlertOutcome> alertOutcomesTable,
            MeterRegistry meterRegistry,
            AppStats appStats,
            AppClock clock,
            @Value("${polysign.ntfy.topic}") String ntfyTopic,
            SignalPerformanceService signalPerformanceService) {
        this.alertsTable               = alertsTable;
        this.watchedWalletsTable       = watchedWalletsTable;
        this.marketsTable              = marketsTable;
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
            long insiderSignatureSamples7d1h) {}

    @GetMapping
    public StatsResponse getStats() {
        // Fix 3: return cached response if still fresh
        long nowMs = clock.now().toEpochMilli();
        StatsResponse cached = cachedStats.get();
        if (cached != null && nowMs - cachedAtMillis < CACHE_TTL_MS) {
            return cached;
        }

        // marketsTracked from the Micrometer gauge registered by MarketPoller
        Gauge trackedGauge = meterRegistry.find("polysign.markets.tracked").gauge();
        long marketsTracked = trackedGauge != null ? (long) trackedGauge.value() : 0L;

        // Fix 2: single scan of alerts table — populates both alertsFiredToday
        // and insiderSignatureCount in one pass (was two independent full scans).
        String todayStart = clock.now()
                .truncatedTo(ChronoUnit.DAYS)
                .toString(); // "2026-04-09T00:00:00Z"
        long alertsFiredToday    = 0;
        long insiderSignatureCount = 0;
        for (Alert a : alertsTable.scan().items()) {
            if (a.getCreatedAt() != null && a.getCreatedAt().compareTo(todayStart) >= 0) {
                alertsFiredToday++;
                if ("insider_signature".equals(a.getType())) {
                    insiderSignatureCount++;
                }
            }
        }

        // watchedWallets: count the watched_wallets table (small — max ~100 entries)
        long watchedWallets = watchedWalletsTable.scan().items().stream().count();

        // Fix 1: walletsSeenToday from in-memory counter — eliminates the 4–7M row scan.
        // Cold-start: counter is empty and ramps up over 24h — acceptable.
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

        // markets in resolution zone: effectivelyResolved() is true in the local markets table
        long marketsInResolutionZone = marketsTable.scan().items().stream()
                .filter(m -> MarketPredicates.effectivelyResolved(m).isPresent())
                .count();

        // Fix 4: bound the alert_outcomes scan to 1000 rows.
        // TODO: add a firedAt GSI to alert_outcomes for a proper 7-day time-bounded query.
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
                insiderSamples);

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
