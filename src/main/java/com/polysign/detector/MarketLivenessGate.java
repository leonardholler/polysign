package com.polysign.detector;

import com.polysign.common.AppClock;
import com.polysign.model.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Gate that returns {@code false} when a market has ended, been paused, or stopped
 * accepting orders — indicating that detector signals would be post-resolution noise.
 *
 * <h3>Block conditions (any one is sufficient)</h3>
 * <ol>
 *   <li>{@code endDate} is non-null and ≤ now — market's nominal expiry has passed.</li>
 *   <li>{@code active} is explicitly {@code false} — Gamma has paused the market.</li>
 *   <li>{@code acceptingOrders} is explicitly {@code false} — CLOB order book is frozen.</li>
 * </ol>
 *
 * <h3>Null-safe defaults</h3>
 * <ul>
 *   <li>{@code endDate == null} → treated as non-expiring; gate passes.</li>
 *   <li>{@code active == null} → treated as {@code true}; gate passes.
 *       This ensures pre-Phase-13 market rows (which lack the field) are not blocked
 *       until the backfill populates them.</li>
 *   <li>{@code acceptingOrders == null} → same as above; gate passes.</li>
 * </ul>
 *
 * <h3>Logging</h3>
 * Logs exactly once per skipped market at INFO level with structured fields:
 * {@code marketId}, {@code reason} (endDate|active|acceptingOrders),
 * {@code endDate}, {@code active}, {@code acceptingOrders}.
 */
@Component
public class MarketLivenessGate {

    private static final Logger log = LoggerFactory.getLogger(MarketLivenessGate.class);

    private final AppClock clock;

    public MarketLivenessGate(AppClock clock) {
        this.clock = clock;
    }

    /**
     * Returns {@code true} if detectors should run for this market, {@code false} otherwise.
     */
    public boolean isLive(Market market) {
        Instant now = clock.now();
        String endDateStr       = market.getEndDate();
        Boolean active          = market.getActive();
        Boolean acceptingOrders = market.getAcceptingOrders();

        // (1) endDate has passed
        if (endDateStr != null) {
            try {
                Instant endDate = Instant.parse(endDateStr);
                if (!endDate.isAfter(now)) {
                    log.info("detector_skipped_market_ended marketId={} reason=endDate endDate={} active={} acceptingOrders={}",
                            market.getMarketId(), endDateStr, active, acceptingOrders);
                    return false;
                }
            } catch (DateTimeParseException ignored) {
                // Unparseable endDate — fail open rather than block.
            }
        }

        // (2) active is explicitly false (null → allow)
        if (Boolean.FALSE.equals(active)) {
            log.info("detector_skipped_market_ended marketId={} reason=active endDate={} active={} acceptingOrders={}",
                    market.getMarketId(), endDateStr, active, acceptingOrders);
            return false;
        }

        // (3) acceptingOrders is explicitly false (null → allow)
        if (Boolean.FALSE.equals(acceptingOrders)) {
            log.info("detector_skipped_market_ended marketId={} reason=acceptingOrders endDate={} active={} acceptingOrders={}",
                    market.getMarketId(), endDateStr, active, acceptingOrders);
            return false;
        }

        return true;
    }
}
