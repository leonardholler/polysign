package com.polysign.common;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared mutable state updated by pollers and read by {@code StatsController}.
 *
 * <p>Fields are {@code volatile} for safe cross-thread reads between the scheduler
 * threads (writers) and the HTTP thread pool (readers). No locking needed —
 * a missed update causes a one-cycle stale value, which is acceptable for a stats bar.
 */
@Component
public class AppStats {

    private volatile Instant lastMarketPollAt;

    // ── Alert counters (reset daily at UTC midnight by resetDailyAlertCounters) ──
    // Cold-start: both start at 0 and ramp up as alerts fire — acceptable.
    private final AtomicLong alertsFiredToday      = new AtomicLong(0);
    private final AtomicLong insiderSigToday       = new AtomicLong(0);

    // Set by MarketPoller after each poll cycle; 0 until the first cycle completes.
    private volatile long marketsInResolutionZone  = 0;

    // Wallet address → last-seen epoch millis (rolling 24h window).
    // Populated by WalletPoller on each trade write; queried by StatsController.
    // Cold-start: map is empty, so walletsSeenToday ramps up over the first 24h — acceptable.
    private final ConcurrentHashMap<String, Long> walletSeen = new ConcurrentHashMap<>();
    private static final int WALLET_MAP_MAX = 200_000;

    public void setLastMarketPollAt(Instant t) { this.lastMarketPollAt = t; }
    public Instant getLastMarketPollAt()        { return lastMarketPollAt;  }

    /** Called by AlertService when a new alert is successfully written to DynamoDB. */
    public void recordAlertFired(String type) {
        alertsFiredToday.incrementAndGet();
        if ("insider_signature".equals(type)) {
            insiderSigToday.incrementAndGet();
        }
    }

    public long getAlertsFiredToday()      { return alertsFiredToday.get(); }
    public long getInsiderSignatureCount() { return insiderSigToday.get();  }

    public void setMarketsInResolutionZone(long count) { this.marketsInResolutionZone = count; }
    public long getMarketsInResolutionZone()           { return marketsInResolutionZone; }

    /** Resets the today-alert counters at UTC midnight. */
    @Scheduled(cron = "0 0 0 * * *")
    public void resetDailyAlertCounters() {
        alertsFiredToday.set(0);
        insiderSigToday.set(0);
    }

    /**
     * Records that {@code address} was active at the current wall-clock time.
     * Safe to call from multiple threads simultaneously.
     */
    public void recordTrade(String address) {
        walletSeen.put(address, System.currentTimeMillis());
        if (walletSeen.size() > WALLET_MAP_MAX) {
            pruneWalletMap();
        }
    }

    /**
     * Returns the number of distinct wallet addresses seen within the last 24 hours,
     * relative to the provided {@code clock}. Accepts a {@link Clock} so tests can
     * inject a fixed instant.
     */
    public long walletsSeenInLast24h(Clock clock) {
        long cutoff = clock.instant().toEpochMilli() - TimeUnit.HOURS.toMillis(24);
        return walletSeen.values().stream().filter(ts -> ts >= cutoff).count();
    }

    /**
     * Prunes entries older than 24h from the wallet map.
     * Runs every 10 minutes as a safety valve against unbounded growth.
     * If the map still exceeds {@link #WALLET_MAP_MAX} after time-based pruning,
     * the oldest half is discarded.
     */
    @Scheduled(fixedDelay = 600_000)
    public void pruneWalletMap() {
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
        walletSeen.entrySet().removeIf(e -> e.getValue() < cutoff);

        if (walletSeen.size() > WALLET_MAP_MAX) {
            List<String> oldest = walletSeen.entrySet().stream()
                    .sorted(Comparator.comparingLong(Map.Entry::getValue))
                    .limit(walletSeen.size() / 2)
                    .map(Map.Entry::getKey)
                    .toList();
            oldest.forEach(walletSeen::remove);
        }
    }
}
