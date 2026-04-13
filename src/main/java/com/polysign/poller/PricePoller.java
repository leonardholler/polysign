package com.polysign.poller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polysign.common.AppClock;
import com.polysign.common.CorrelationId;
import com.polysign.model.Market;
import com.polysign.model.PriceSnapshot;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Polls the Polymarket CLOB API for YES-token midpoint prices and writes
 * {@link PriceSnapshot} records to DynamoDB with a 7-day TTL.
 *
 * <p>Design decisions:
 * <ul>
 *   <li>Reads yesTokenId directly from the market row — no JSON re-parsing per cycle.</li>
 *   <li>No-change dedupe via {@code BigDecimal.setScale(4, HALF_UP).compareTo()} — "0.515"
 *       and "0.5150" are treated as equal and do not produce a new snapshot.</li>
 *   <li>Each CLOB call is wrapped in RateLimiter(10/s) + Retry + CircuitBreaker.</li>
 *   <li>Per-market catch — one bad market never kills the scan loop.</li>
 * </ul>
 */
@Component
public class PricePoller {

    private static final Logger log = LoggerFactory.getLogger(PricePoller.class);

    private static final String  CLOB_CB_NAME   = "polymarket-clob";
    private static final long    TTL_SECONDS     = 30L * 24 * 60 * 60; // 30 days (extended for backtesting)
    private static final int     DEDUPE_SCALE    = 4;

    private final WebClient               clobClient;
    private final DynamoDbTable<Market>   marketsTable;
    private final DynamoDbTable<PriceSnapshot> snapshotsTable;
    private final AppClock                clock;
    private final ObjectMapper            mapper;
    private final CircuitBreaker          circuitBreaker;
    private final Retry                   retry;
    private final RateLimiter             rateLimiter;
    private final Counter                 pricesPolledCounter;

    public PricePoller(
            @Qualifier("clobApiClient") WebClient clobClient,
            DynamoDbTable<Market> marketsTable,
            DynamoDbTable<PriceSnapshot> priceSnapshotsTable,
            AppClock clock,
            ObjectMapper mapper,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry,
            RateLimiterRegistry rateLimiterRegistry,
            MeterRegistry meterRegistry) {

        this.clobClient     = clobClient;
        this.marketsTable   = marketsTable;
        this.snapshotsTable = priceSnapshotsTable;
        this.clock          = clock;
        this.mapper         = mapper;
        this.circuitBreaker = cbRegistry.circuitBreaker(CLOB_CB_NAME);
        this.retry          = retryRegistry.retry(CLOB_CB_NAME);
        this.rateLimiter    = rateLimiterRegistry.rateLimiter(CLOB_CB_NAME);

        this.pricesPolledCounter = Counter.builder("polysign.prices.polled")
             .description("Total price snapshots written to DynamoDB (no-change dedupe applied)")
             .register(meterRegistry);
    }

    @Scheduled(
        fixedDelayString   = "${polysign.pollers.price.interval-ms:60000}",
        initialDelayString = "${polysign.pollers.price.initial-delay-ms:30000}"
    )
    public void pollPrices() {
        try (var ignored = CorrelationId.set()) {
            log.info("price_poll_start");

            int written  = 0;
            int skipped  = 0;
            int errors   = 0;

            // Full table scan — markets table is small in practice (< 10 k rows).
            // PageIterable<T>.items() flattens all pages into a single Iterable<T>.
            for (Market market : marketsTable.scan().items()) {
                    String yesTokenId = market.getYesTokenId();
                    if (yesTokenId == null || yesTokenId.isBlank()) {
                        log.debug("price_poll_skip_no_token marketId={}", market.getMarketId());
                        continue;
                    }
                    try {
                        boolean didWrite = pollMarketPrice(market, yesTokenId);
                        if (didWrite) {
                            written++;
                            pricesPolledCounter.increment();
                        } else {
                            skipped++;
                        }
                    } catch (WebClientResponseException.NotFound e) {
                        log.debug("price_poll_skip_delisted marketId={} tokenId={}",
                                  market.getMarketId(), yesTokenId);
                        skipped++;
                    } catch (Exception e) {
                        errors++;
                        log.warn("price_poll_market_error marketId={} error={}",
                                 market.getMarketId(), e.getMessage(), e);
                    }
            }

            log.info("price_poll_complete written={} skipped_no_change={} errors={}", written, skipped, errors);

        } catch (Exception e) {
            log.error("price_poll_failed error={}", e.getMessage(), e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Polls CLOB for the latest midpoint, applies 4dp no-change dedupe, and writes
     * a snapshot if the price has moved.
     *
     * @return {@code true} if a new snapshot was written; {@code false} if deduped.
     */
    private boolean pollMarketPrice(Market market, String yesTokenId) {
        BigDecimal yesPrice = fetchMidpoint(yesTokenId);
        BigDecimal noPrice  = BigDecimal.ONE.subtract(yesPrice);
        BigDecimal midpoint = yesPrice; // YES price IS the market midpoint

        // ── No-change dedupe: compare at 4 decimal places ─────────────────────
        Optional<PriceSnapshot> latest = queryLatestSnapshot(market.getMarketId());
        if (latest.isPresent()) {
            BigDecimal prevScaled = latest.get().getYesPrice()
                                         .setScale(DEDUPE_SCALE, RoundingMode.HALF_UP);
            BigDecimal currScaled = yesPrice.setScale(DEDUPE_SCALE, RoundingMode.HALF_UP);
            if (prevScaled.compareTo(currScaled) == 0) {
                log.debug("price_no_change marketId={} price={}", market.getMarketId(), currScaled);
                return false;
            }
        }

        // ── Write snapshot with 7-day TTL ────────────────────────────────────
        PriceSnapshot snap = new PriceSnapshot();
        snap.setMarketId(market.getMarketId());
        snap.setTimestamp(clock.nowIso());
        snap.setYesPrice(yesPrice);
        snap.setNoPrice(noPrice);
        snap.setMidpoint(midpoint);
        snap.setVolume24h(parseVolume24h(market.getVolume24h()));
        snap.setExpiresAt(clock.nowEpochSeconds() + TTL_SECONDS);

        snapshotsTable.putItem(snap);

        // Denormalize current price onto the Market row so the API can batch-load
        // market context (question, price, volume) without querying price_snapshots.
        try {
            market.setCurrentYesPrice(yesPrice);
            marketsTable.updateItem(market);
        } catch (Exception e) {
            log.warn("price_market_update_failed marketId={} error={}", market.getMarketId(), e.getMessage());
        }

        log.debug("price_snapshot_written marketId={} yes={} no={}",
                  market.getMarketId(), yesPrice, noPrice);
        return true;
    }

    /**
     * Fetches the YES-token midpoint from CLOB, wrapped in RateLimiter + Retry + CircuitBreaker.
     * Response shape: {@code {"mid": "0.515"}}
     */
    private BigDecimal fetchMidpoint(String tokenId) {
        Supplier<BigDecimal> call = () -> {
            String body = clobClient.get()
                .uri(u -> u.path("/midpoint").queryParam("token_id", tokenId).build())
                .retrieve()
                .bodyToMono(String.class)
                .block();
            try {
                JsonNode node = mapper.readTree(body);
                String   mid  = node.path("mid").asText();
                return new BigDecimal(mid);
            } catch (Exception e) {
                throw new RuntimeException("CLOB midpoint parse failure tokenId=" + tokenId, e);
            }
        };

        // Apply RateLimiter → Retry → CircuitBreaker (outer-to-inner order).
        // RateLimiter is outermost so we throttle before consuming retry budget.
        return RateLimiter.decorateSupplier(rateLimiter,
               Retry.decorateSupplier(retry,
               CircuitBreaker.decorateSupplier(circuitBreaker, call))).get();
    }

    /**
     * Returns the latest PriceSnapshot for the given marketId, sorted by timestamp DESC.
     * Uses DynamoDB Enhanced Client query with scanIndexForward=false, limit=1.
     */
    private Optional<PriceSnapshot> queryLatestSnapshot(String marketId) {
        var qc = QueryConditional.keyEqualTo(Key.builder().partitionValue(marketId).build());
        return snapshotsTable.query(r -> r.queryConditional(qc)
                                          .scanIndexForward(false)
                                          .limit(1))
                             .items()
                             .stream()
                             .findFirst();
    }

    /** Parses the market's volume24h string into a BigDecimal, or null if blank/unparseable. */
    private static BigDecimal parseVolume24h(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return new BigDecimal(raw); } catch (NumberFormatException e) { return null; }
    }
}
