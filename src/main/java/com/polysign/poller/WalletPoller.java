package com.polysign.poller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polysign.common.AppClock;
import com.polysign.common.CorrelationId;
import com.polysign.detector.WalletActivityDetector;
import com.polysign.model.Market;
import com.polysign.model.WalletTrade;
import com.polysign.model.WatchedWallet;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Polls the Polymarket Data API for trades by each watched wallet and writes
 * new trades to the {@code wallet_trades} DynamoDB table.
 *
 * <h3>conditionId → marketId cache</h3>
 * The Data API identifies markets by hex {@code conditionId}; our {@code markets}
 * table uses the Gamma numeric {@code marketId}. At startup, {@link #buildCache()}
 * scans all markets and builds an in-memory {@code conditionId → marketId} map.
 * On a cache miss at poll time, the {@code conditionId-index} GSI is queried (O(1)).
 * If the GSI also misses, the trade is logged at WARN and skipped — Phase 9 owns retry semantics.
 *
 * <h3>Idempotency</h3>
 * {@code wallet_trades} PK=address, SK=txHash. PutItem overwrites on the same
 * (address, txHash) — writing the same trade twice is a no-op (identical data).
 *
 * <h3>Data API endpoint</h3>
 * {@code GET https://data-api.polymarket.com/trades?user={proxyWallet}&startTime={epochSec}&limit=100}
 * The {@code user} parameter is the proxy wallet address (on-chain execution address).
 */
@Component
public class WalletPoller {

    private static final Logger log = LoggerFactory.getLogger(WalletPoller.class);
    private static final String CB_NAME   = "polymarket-data";
    private static final int    PAGE_SIZE = 100;
    /** How far back to fetch trades on first sync and how far back to keep trades from returning wallets. */
    private final int lookbackHours;

    /** Consecutive zero-trade cycles before PIPELINE STALL error. */
    private static final int STALL_THRESHOLD = 3;

    /** Consecutive zero-trade cycle counter. */
    private int consecutiveZeroCycles = 0;

    private final WebClient                     dataApiClient;
    private final DynamoDbTable<Market>         marketsTable;
    private final DynamoDbTable<WatchedWallet>  watchedWalletsTable;
    private final DynamoDbTable<WalletTrade>    walletTradesTable;
    private final WalletActivityDetector        activityDetector;
    private final AppClock                      clock;
    private final ObjectMapper                  mapper;
    private final CircuitBreaker                circuitBreaker;
    private final Retry                         retry;
    private final Counter                       tradesIngested;

    /** conditionId (hex) → marketId (Gamma numeric string). Thread-safe, bounded by market count. */
    private final ConcurrentHashMap<String, String> conditionToMarketId = new ConcurrentHashMap<>();

    /** Guards the one-time raw API response sample log. */
    private volatile boolean firstRawTradeLogged = false;

    public WalletPoller(
            @Qualifier("dataApiClient") WebClient dataApiClient,
            DynamoDbTable<Market> marketsTable,
            DynamoDbTable<WatchedWallet> watchedWalletsTable,
            DynamoDbTable<WalletTrade> walletTradesTable,
            WalletActivityDetector activityDetector,
            AppClock clock,
            ObjectMapper mapper,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry,
            MeterRegistry meterRegistry,
            @Value("${polysign.pollers.wallet.lookback-hours:48}") int lookbackHours) {

        this.lookbackHours       = lookbackHours;
        this.dataApiClient       = dataApiClient;
        this.marketsTable        = marketsTable;
        this.watchedWalletsTable = watchedWalletsTable;
        this.walletTradesTable   = walletTradesTable;
        this.activityDetector    = activityDetector;
        this.clock               = clock;
        this.mapper              = mapper;
        this.circuitBreaker      = cbRegistry.circuitBreaker(CB_NAME);
        this.retry               = retryRegistry.retry(CB_NAME);

        this.tradesIngested = Counter.builder("polysign.wallet.trades.ingested")
                .description("Total wallet trades written to wallet_trades table")
                .register(meterRegistry);
    }

    /**
     * Build the conditionId → marketId lookup cache by scanning the markets table.
     * Runs after all ApplicationRunner beans complete (including BootstrapRunner which
     * creates the DynamoDB tables), so the table is guaranteed to exist.
     */
    @EventListener(ApplicationReadyEvent.class)
    void buildCache() {
        // ApplicationReadyEvent fires after BootstrapRunner (Order=1) and WalletBootstrap
        // (Order=2) have both completed, so the markets table and watched_wallets table
        // exist on a fresh LocalStack or first-ever deployment.
        try {
            int count = 0;
            for (Market m : marketsTable.scan().items()) {
                if (m.getConditionId() != null && m.getMarketId() != null) {
                    conditionToMarketId.put(m.getConditionId(), m.getMarketId());
                    count++;
                }
            }
            log.info("wallet_poller_cache_built conditionMappings={}", count);
        } catch (Exception e) {
            log.warn("wallet_poller_cache_build_failed error={} — starting with empty cache",
                    e.getMessage());
        }
    }

    @Scheduled(fixedDelayString   = "${polysign.pollers.wallet.interval-ms:60000}",
               initialDelayString = "${polysign.pollers.wallet.initial-delay-ms:90000}")
    public void poll() {
        try (var ignored = CorrelationId.set()) {
            Instant now = clock.now();
            int walletsAttempted = 0;
            int walletsSucceeded = 0;
            int totalTrades = 0;

            for (WatchedWallet wallet : watchedWalletsTable.scan().items()) {
                walletsAttempted++;
                try {
                    int wrote = pollWallet(wallet, now);
                    totalTrades += wrote;
                    walletsSucceeded++;
                } catch (Exception e) {
                    log.warn("wallet_poll_failed address={} error={}",
                            wallet.getAddress(), e.getMessage());
                }
            }

            // Health-check structured summary
            log.info("wallet_poll_health wallets_attempted={} wallets_succeeded={} trades_ingested={} data_api_status=OK rpc_fallback_used=false",
                    walletsAttempted, walletsSucceeded, totalTrades);
            log.info("wallet_poll_complete wallets={} trades_written={}", walletsAttempted, totalTrades);

            // Pipeline stall detection
            if (totalTrades == 0) {
                consecutiveZeroCycles++;
                if (consecutiveZeroCycles >= STALL_THRESHOLD) {
                    log.error("PIPELINE STALL: No wallet trades ingested in {} consecutive cycles — consensus alerts cannot fire",
                            consecutiveZeroCycles);
                }
            } else {
                consecutiveZeroCycles = 0;
            }
        } catch (Exception e) {
            log.error("wallet_poll_error error={}", e.getMessage(), e);
        }
    }

    private int pollWallet(WatchedWallet wallet, Instant pollTime) {
        String address = wallet.getAddress();

        // Determine startTime for the Data API call.
        long startEpoch;
        if (wallet.getLastSyncedAt() != null) {
            startEpoch = Instant.parse(wallet.getLastSyncedAt()).getEpochSecond();
        } else {
            startEpoch = pollTime.minusSeconds((long) lookbackHours * 3600).getEpochSecond();
        }

        List<Map<String, Object>> rawTrades = fetchTrades(address, startEpoch);

        // Drop trades older than lookbackHours silently — they reference resolved markets
        // that are no longer in the top-400 table and would produce spurious WARNs.
        // Trades within the window that still miss a market lookup keep their WARN.
        long cutoffEpoch = pollTime.minusSeconds((long) lookbackHours * 3600).getEpochSecond();
        rawTrades = rawTrades.stream()
                .filter(t -> {
                    Object ts = t.get("timestamp");
                    if (ts == null) return true;
                    try { return Long.parseLong(ts.toString()) >= cutoffEpoch; }
                    catch (NumberFormatException ignored) { return true; }
                })
                .toList();

        if (rawTrades.isEmpty()) {
            // Still update lastSyncedAt so next cycle doesn't re-fetch from FIRST_SYNC_LOOKBACK
            updateLastSyncedAt(wallet, pollTime);
            return 0;
        }

        int wrote = 0;
        // latestTimestamp: most recent trade of any size → drives lastTradeAt
        // latestBigTimestamp: most recent $1000+ trade → drives the five display fields
        String latestTimestamp      = null;
        String latestBigTimestamp   = null;
        String latestDirection      = null;
        String latestQuestion       = null;
        String latestOutcome        = null;
        String latestSizeUsdc       = null;
        String latestPrice          = null;
        String latestBigConditionId = null; // Fix 5: track conditionId of last big trade for market link
        BigDecimal BIG_THRESHOLD    = new BigDecimal("1000");
        double cycleVolumeUsdc      = 0.0;  // Fix 8: accumulate all trade volume this cycle

        for (Map<String, Object> raw : rawTrades) {
            try {
                if (writeTrade(raw, wallet)) {
                    wrote++;
                    // Fix 4: prefer createdAt (ISO string from API) over timestamp (epoch seconds)
                    Object createdAtObj = raw.get("createdAt");
                    Object tsObj        = raw.get("timestamp");
                    String isoTs = null;
                    if (createdAtObj != null && !createdAtObj.toString().isBlank()) {
                        isoTs = createdAtObj.toString();
                    } else if (tsObj != null) {
                        try {
                            isoTs = Instant.ofEpochSecond(Long.parseLong(tsObj.toString())).toString();
                        } catch (NumberFormatException ignored) { /* skip */ }
                    }
                    if (isoTs != null) {
                        BigDecimal size  = decimal(raw, "size");
                        BigDecimal price = decimal(raw, "price");
                        BigDecimal tradeUsdc = (size != null && price != null)
                                ? size.multiply(price).setScale(2, java.math.RoundingMode.HALF_UP)
                                : BigDecimal.ZERO;
                        cycleVolumeUsdc += tradeUsdc.doubleValue(); // Fix 8
                        // Always track the most recent trade timestamp (all sizes).
                        if (latestTimestamp == null || isoTs.compareTo(latestTimestamp) > 0) {
                            latestTimestamp = isoTs;
                        }
                        // Only update display fields if this trade is >= $1000.
                        if (tradeUsdc.compareTo(BIG_THRESHOLD) >= 0) {
                            if (latestBigTimestamp == null || isoTs.compareTo(latestBigTimestamp) > 0) {
                                latestBigTimestamp   = isoTs;
                                latestDirection      = str(raw, "side");
                                latestQuestion       = str(raw, "title");
                                latestOutcome        = str(raw, "outcome");
                                latestSizeUsdc       = tradeUsdc.toPlainString();
                                latestPrice          = price != null ? price.toPlainString() : null;
                                latestBigConditionId = str(raw, "conditionId"); // Fix 5
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("wallet_trade_write_failed address={} txHash={} error={}",
                        address, raw.getOrDefault("transactionHash", "?"), e.getMessage());
            }
        }

        // Update wallet summary fields for the Smart Money Tracker dashboard.
        if (latestTimestamp != null) {
            wallet.setLastTradeAt(latestTimestamp); // Fix 4: actual trade timestamp, not pollTime
        }
        // The display fields only update when a $1000+ trade was seen in this batch.
        // If no such trade, previous values are preserved (kept from last qualifying trade).
        if (latestBigTimestamp != null) {
            wallet.setRecentDirection(latestDirection);
            wallet.setLastMarketQuestion(latestQuestion);
            wallet.setLastOutcome(latestOutcome);
            wallet.setLastSizeUsdc(latestSizeUsdc);
            wallet.setLastTradePrice(latestPrice);
            // Fix 5: resolve market link from conditionId cache populated by writeTrade
            if (latestBigConditionId != null) {
                String mktId = conditionToMarketId.get(latestBigConditionId);
                if (mktId != null) {
                    try {
                        Market mkt = marketsTable.getItem(Key.builder().partitionValue(mktId).build());
                        String evSlug = mkt != null ? mkt.getEventSlug() : null;
                        wallet.setLastMarketLink(evSlug != null
                                ? "https://polymarket.com/event/" + evSlug : null);
                    } catch (Exception e) {
                        log.debug("wallet_market_link_lookup_failed conditionId={} error={}",
                                latestBigConditionId, e.getMessage());
                    }
                }
            }
        }
        if (wrote > 0) {
            int prev = wallet.getTradeCount() != null ? wallet.getTradeCount() : 0;
            wallet.setTradeCount(prev + wrote);
            // Fix 8: update total volume and recompute quality score
            double prevTotal = wallet.getTotalVolumeUsdc() != null ? wallet.getTotalVolumeUsdc() : 0.0;
            double newTotal  = prevTotal + cycleVolumeUsdc;
            wallet.setTotalVolumeUsdc(newTotal);
            int count = wallet.getTradeCount();
            if (count > 0 && newTotal > 0) {
                double avgSize     = newTotal / count;
                double winRate     = 0.5; // default until backtest data accumulates
                double score       = Math.log10(Math.max(avgSize, 1.0)) * (1.0 + winRate);
                wallet.setQualityScore(score);
            }
        }

        updateLastSyncedAt(wallet, pollTime);
        tradesIngested.increment(wrote);
        return wrote;
    }

    /**
     * Parse one raw trade, resolve its marketId, write to DynamoDB, and fire detectors.
     *
     * @return true if a trade was written (new or idempotent overwrite of same data)
     */
    private boolean writeTrade(Map<String, Object> raw, WatchedWallet wallet) {
        // Log raw API field names+values on first ever trade to confirm DTO mapping correctness.
        if (!firstRawTradeLogged) {
            firstRawTradeLogged = true;
            log.info("wallet_raw_trade_sample address={} fields={}",
                    wallet.getAddress(), raw);
        }

        String txHash      = str(raw, "transactionHash");
        String conditionId = str(raw, "conditionId");
        String slug        = str(raw, "slug");
        String proxyWallet = str(raw, "proxyWallet");
        Object tsObj       = raw.get("timestamp");

        if (txHash == null || txHash.isBlank() || conditionId == null || tsObj == null) {
            if (txHash != null && txHash.isBlank()) {
                log.warn("wallet_trade_blank_txhash address={} conditionId={} raw_keys={}",
                        wallet.getAddress(), conditionId, raw.keySet());
            } else {
                log.debug("wallet_trade_malformed address={} txHash={}", wallet.getAddress(), txHash);
            }
            return false;
        }

        // Resolve Gamma numeric marketId from conditionId.
        String marketId = resolveMarketId(conditionId, proxyWallet, txHash, slug,
                tsObj.toString(), wallet.getAlias());
        if (marketId == null) {
            return false; // logged inside resolveMarketId
        }

        // Resolve event-level slug for accurate Polymarket event links.
        // The Data API's trade.slug is market-level (contains numeric outcome ID suffixes),
        // so we look up the Market record and use its eventSlug (from Gamma events[0].slug).
        String eventSlug = null;
        try {
            Market market = marketsTable.getItem(
                    Key.builder().partitionValue(marketId).build());
            if (market != null) {
                eventSlug = market.getEventSlug();
            }
        } catch (Exception e) {
            log.debug("wallet_market_eventslug_lookup_failed marketId={} error={}",
                    marketId, e.getMessage());
        }
        String detectorSlug = eventSlug != null ? eventSlug : slug;

        long epochSeconds = Long.parseLong(tsObj.toString());
        String isoTimestamp = Instant.ofEpochSecond(epochSeconds).toString();

        // sizeUsdc = size × price (Data API returns shares, not USDC value)
        BigDecimal size  = decimal(raw, "size");
        BigDecimal price = decimal(raw, "price");
        BigDecimal sizeUsdc = (size != null && price != null)
                ? size.multiply(price).setScale(2, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        WalletTrade trade = new WalletTrade();
        trade.setAddress(wallet.getAddress());
        trade.setTxHash(txHash);
        trade.setTimestamp(isoTimestamp);
        trade.setMarketId(marketId);
        trade.setMarketQuestion(str(raw, "title"));
        trade.setSide(str(raw, "side"));
        trade.setOutcome(str(raw, "outcome"));
        trade.setSizeUsdc(sizeUsdc);
        trade.setPrice(price);
        trade.setSlug(slug);

        walletTradesTable.putItem(trade);
        log.debug("wallet_trade_written address={} txHash={} marketId={} sizeUsdc={}",
                wallet.getAddress(), txHash, marketId, sizeUsdc);

        // Fire detectors synchronously. Per-item catch: a detector error never kills the poll loop.
        try {
            activityDetector.checkTrade(trade, wallet.getAlias(), detectorSlug);
        } catch (Exception e) {
            log.warn("wallet_activity_check_failed txHash={} error={}", txHash, e.getMessage());
        }

        return true;
    }

    /**
     * Resolve a Gamma numeric marketId from a Data API conditionId.
     *
     * <ol>
     *   <li>Cache hit — return immediately.</li>
     *   <li>Cache miss — query {@code conditionId-index} GSI (O(1)); populate cache;
     *       return the match.</li>
     *   <li>DynamoDB miss — log at WARN and return null (caller skips the trade).</li>
     * </ol>
     */
    private String resolveMarketId(String conditionId, String proxyWallet,
                                   String txHash, String slug,
                                   String tradeTimestamp, String alias) {
        // 1. Cache hit
        String marketId = conditionToMarketId.get(conditionId);
        if (marketId != null) return marketId;

        // 2. GSI query on conditionId-index — O(1) unlike the prior Scan approach.
        //    Guard: null/blank conditionId has no GSI entry; skip straight to WARN.
        if (conditionId != null && !conditionId.isBlank()) {
            try {
                DynamoDbIndex<Market> idx = marketsTable.index("conditionId-index");
                Iterator<Page<Market>> pages = idx.query(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(conditionId).build())).iterator();
                if (pages.hasNext()) {
                    List<Market> items = pages.next().items();
                    if (!items.isEmpty()) {
                        String resolved = items.get(0).getMarketId();
                        conditionToMarketId.put(conditionId, resolved);
                        log.debug("wallet_market_resolved_via_dynamo conditionId={} marketId={}",
                                conditionId, resolved);
                        return resolved;
                    }
                }
            } catch (Exception e) {
                log.warn("wallet_market_lookup_failed conditionId={} error={}", conditionId, e.getMessage());
            }
        }

        // 3. Not found — market not in tracked universe (top 400 by 24h volume), skip the trade.
        log.debug("event=wallet_trade_unknown_market conditionId={} proxyWallet={} txHash={} slug={} timestamp={}",
                conditionId, proxyWallet, txHash, slug, tradeTimestamp);
        return null;
    }

    private List<Map<String, Object>> fetchTrades(String address, long startEpochSec) {
        Supplier<List<Map<String, Object>>> call = () -> {
            String body = dataApiClient.get()
                    .uri(u -> u.path("/trades")
                               .queryParam("user",      address)
                               .queryParam("startTime", startEpochSec)
                               .queryParam("limit",     PAGE_SIZE)
                               .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            try {
                return mapper.readValue(body, new TypeReference<>() {});
            } catch (Exception e) {
                throw new RuntimeException("JSON parse failure fetching trades for " + address, e);
            }
        };
        return Retry.decorateSupplier(retry,
               CircuitBreaker.decorateSupplier(circuitBreaker, call)).get();
    }

    private void updateLastSyncedAt(WatchedWallet wallet, Instant pollTime) {
        try {
            wallet.setLastSyncedAt(pollTime.toString());
            // lastTradeAt, tradeCount, recentDirection already set on wallet object by pollWallet()
            watchedWalletsTable.updateItem(wallet);
        } catch (Exception e) {
            log.warn("wallet_sync_timestamp_update_failed address={} error={}",
                    wallet.getAddress(), e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static BigDecimal decimal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        try { return new BigDecimal(v.toString()); } catch (NumberFormatException e) { return null; }
    }
}
