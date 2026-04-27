package com.polysign.common;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
}
