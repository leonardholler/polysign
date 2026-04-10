package com.polysign.common;

import org.springframework.stereotype.Component;

import java.time.Instant;

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

    public void setLastMarketPollAt(Instant t) { this.lastMarketPollAt = t; }
    public Instant getLastMarketPollAt()        { return lastMarketPollAt;   }
}
