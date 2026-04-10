package com.polysign.api;

import com.polysign.common.AppClock;
import com.polysign.common.AppStats;
import com.polysign.model.Alert;
import com.polysign.model.WatchedWallet;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

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
    private final MeterRegistry                meterRegistry;
    private final AppStats                     appStats;
    private final AppClock                     clock;
    private final String                       ntfyTopic;

    public StatsController(
            DynamoDbTable<Alert> alertsTable,
            DynamoDbTable<WatchedWallet> watchedWalletsTable,
            MeterRegistry meterRegistry,
            AppStats appStats,
            AppClock clock,
            @Value("${polysign.ntfy.topic}") String ntfyTopic) {
        this.alertsTable         = alertsTable;
        this.watchedWalletsTable = watchedWalletsTable;
        this.meterRegistry       = meterRegistry;
        this.appStats            = appStats;
        this.clock               = clock;
        this.ntfyTopic           = ntfyTopic;
    }

    public record StatsResponse(
            long marketsTracked,
            long alertsFiredToday,
            long watchedWallets,
            String lastPollTime,
            String ntfyTopic) {}

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

        // lastPollTime from AppStats (set by MarketPoller after each successful cycle)
        Instant lastPoll = appStats.getLastMarketPollAt();

        return new StatsResponse(
                marketsTracked,
                alertsFiredToday,
                watchedWallets,
                lastPoll != null ? lastPoll.toString() : null,
                ntfyTopic);
    }
}
