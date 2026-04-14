package com.polysign.api;

import com.polysign.backtest.MarketPredicates;
import com.polysign.backtest.SignalPerformanceService;
import com.polysign.backtest.SignalPerformanceService.AggregatePrecision;
import com.polysign.common.AppClock;
import com.polysign.common.AppStats;
import com.polysign.model.Alert;
import com.polysign.model.AlertOutcome;
import com.polysign.model.Market;
import com.polysign.model.WalletTrade;
import com.polysign.model.WatchedWallet;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Lightweight stats endpoint for the dashboard header bar.
 *
 * GET /api/stats returns:
 * <ul>
 *   <li>marketsTracked  — from the Micrometer gauge set by MarketPoller</li>
 *   <li>alertsFiredToday — alerts table scan filtered to today UTC</li>
 *   <li>watchedWallets  — watched_wallets table count</li>
 *   <li>lastPollTime    — last MarketPoller cycle completion time (ISO-8601)</li>
 *   <li>ntfyTopic       — the configured ntfy.sh topic name</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final DynamoDbTable<Alert>         alertsTable;
    private final DynamoDbTable<WatchedWallet> watchedWalletsTable;
    private final DynamoDbTable<Market>        marketsTable;
    private final DynamoDbTable<AlertOutcome>  alertOutcomesTable;
    private final DynamoDbTable<WalletTrade>   walletTradesTable;
    private final MeterRegistry                meterRegistry;
    private final AppStats                     appStats;
    private final AppClock                     clock;
    private final String                       ntfyTopic;
    private final SignalPerformanceService     signalPerformanceService;

    public StatsController(
            DynamoDbTable<Alert> alertsTable,
            DynamoDbTable<WatchedWallet> watchedWalletsTable,
            DynamoDbTable<Market> marketsTable,
            DynamoDbTable<AlertOutcome> alertOutcomesTable,
            DynamoDbTable<WalletTrade> walletTradesTable,
            MeterRegistry meterRegistry,
            AppStats appStats,
            AppClock clock,
            @Value("${polysign.ntfy.topic}") String ntfyTopic,
            SignalPerformanceService signalPerformanceService) {
        this.alertsTable               = alertsTable;
        this.watchedWalletsTable       = watchedWalletsTable;
        this.marketsTable              = marketsTable;
        this.alertOutcomesTable        = alertOutcomesTable;
        this.walletTradesTable         = walletTradesTable;
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
        // marketsTracked from the Micrometer gauge registered by MarketPoller
        Gauge trackedGauge = meterRegistry.find("polysign.markets.tracked").gauge();
        long marketsTracked = trackedGauge != null ? (long) trackedGauge.value() : 0L;

        // alertsFiredToday: scan alerts table, filter to createdAt >= today 00:00:00Z
        String todayStart = clock.now()
                .truncatedTo(ChronoUnit.DAYS)
                .toString(); // "2026-04-09T00:00:00Z"
        long alertsFiredToday = alertsTable.scan().items().stream()
                .filter(a -> a.getCreatedAt() != null
                        && a.getCreatedAt().compareTo(todayStart) >= 0)
                .count();

        // watchedWallets: count the watched_wallets table (small — max ~100 entries)
        long watchedWallets = watchedWalletsTable.scan().items().stream().count();

        // walletsSeenToday: distinct trader addresses in wallet_trades, last 24h
        String since24h = clock.now().minus(Duration.ofHours(24)).toString();
        long walletsSeenToday = walletTradesTable.scan().items().stream()
                .filter(t -> t.getTimestamp() != null && t.getTimestamp().compareTo(since24h) >= 0)
                .map(WalletTrade::getAddress)
                .distinct()
                .count();

        // insiderSignatureCount: insider_signature alerts today
        long insiderSignatureCount = alertsTable.scan().items().stream()
                .filter(a -> "insider_signature".equals(a.getType()))
                .filter(a -> a.getCreatedAt() != null && a.getCreatedAt().compareTo(todayStart) >= 0)
                .count();

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

        // Single scan of alert_outcomes — collect resolution rows, then derive counts.
        List<AlertOutcome> resolutionOutcomes = alertOutcomesTable.scan().items().stream()
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

        return new StatsResponse(
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
    }

    /** Returns true when priceAtHorizon is a decisive binary outcome (>= 0.99 or <= 0.01). */
    private static boolean isFullyResolved(BigDecimal price) {
        if (price == null) return false;
        double v = price.doubleValue();
        return v >= 0.99 || v <= 0.01;
    }
}
