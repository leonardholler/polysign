package com.polysign.poller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polysign.common.AppClock;
import com.polysign.common.CorrelationId;
import com.polysign.detector.ConsensusDetector;
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
import jakarta.annotation.PostConstruct;
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
import java.time.Duration;
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
    /** Default look-back for first sync — wallets with null lastSyncedAt. */
    private static final Duration FIRST_SYNC_LOOKBACK = Duration.ofHours(24);

    private final WebClient                     dataApiClient;
    private final DynamoDbTable<Market>         marketsTable;
    private final DynamoDbTable<WatchedWallet>  watchedWalletsTable;
    private final DynamoDbTable<WalletTrade>    walletTradesTable;
    private final WalletActivityDetector        activityDetector;
    private final ConsensusDetector             consensusDetector;
    private final AppClock                      clock;
    private final ObjectMapper                  mapper;
    private final CircuitBreaker                circuitBreaker;
    private final Retry                         retry;
    private final Counter                       tradesIngested;

    /** conditionId (hex) → marketId (Gamma numeric string). Thread-safe, bounded by market count. */
    private final ConcurrentHashMap<String, String> conditionToMarketId = new ConcurrentHashMap<>();

    public WalletPoller(
            @Qualifier("dataApiClient") WebClient dataApiClient,
            DynamoDbTable<Market> marketsTable,
            DynamoDbTable<WatchedWallet> watchedWalletsTable,
            DynamoDbTable<WalletTrade> walletTradesTable,
            WalletActivityDetector activityDetector,
            ConsensusDetector consensusDetector,
            AppClock clock,
            ObjectMapper mapper,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry,
            MeterRegistry meterRegistry) {

        this.dataApiClient       = dataApiClient;
        this.marketsTable        = marketsTable;
        this.watchedWalletsTable = watchedWalletsTable;
        this.walletTradesTable   = walletTradesTable;
        this.activityDetector    = activityDetector;
        this.consensusDetector   = consensusDetector;
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
     * Runs once at startup after DynamoDB tables are ready (WalletBootstrap is Order=2,
     * WalletPoller is a regular @Component so Spring initialises it after runners complete).
     */
    @PostConstruct
    void buildCache() {
        // Runs before ApplicationRunner beans (BootstrapRunner), so the markets table
        // may not exist yet on a fresh LocalStack or first-ever deployment. Treat a
        // missing table as an empty cache — the on-miss GSI query path handles all
        // lookups at runtime until MarketPoller populates the table.
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
            int walletCount = 0;
            int totalTrades = 0;

            for (WatchedWallet wallet : watchedWalletsTable.scan().items()) {
                try {
                    int wrote = pollWallet(wallet, now);
                    totalTrades += wrote;
                    walletCount++;
                } catch (Exception e) {
                    log.warn("wallet_poll_failed address={} error={}",
                            wallet.getAddress(), e.getMessage());
                }
            }

            log.info("wallet_poll_complete wallets={} trades_written={}", walletCount, totalTrades);
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
            startEpoch = pollTime.minus(FIRST_SYNC_LOOKBACK).getEpochSecond();
        }

        List<Map<String, Object>> rawTrades = fetchTrades(address, startEpoch);
        if (rawTrades.isEmpty()) {
            // Still update lastSyncedAt so next cycle doesn't re-fetch from FIRST_SYNC_LOOKBACK
            updateLastSyncedAt(wallet, pollTime);
            return 0;
        }

        int wrote = 0;
        for (Map<String, Object> raw : rawTrades) {
            try {
                if (writeTrade(raw, wallet)) {
                    wrote++;
                }
            } catch (Exception e) {
                log.warn("wallet_trade_write_failed address={} txHash={} error={}",
                        address, raw.getOrDefault("transactionHash", "?"), e.getMessage());
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
        String txHash      = str(raw, "transactionHash");
        String conditionId = str(raw, "conditionId");
        String slug        = str(raw, "slug");
        String proxyWallet = str(raw, "proxyWallet");
        Object tsObj       = raw.get("timestamp");

        if (txHash == null || conditionId == null || tsObj == null) {
            log.debug("wallet_trade_malformed address={} txHash={}", wallet.getAddress(), txHash);
            return false;
        }

        // Resolve Gamma numeric marketId from conditionId.
        String marketId = resolveMarketId(conditionId, proxyWallet, txHash, slug,
                tsObj.toString(), wallet.getAlias());
        if (marketId == null) {
            return false; // logged inside resolveMarketId
        }

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
            activityDetector.checkTrade(trade, wallet.getAlias(), slug);
        } catch (Exception e) {
            log.warn("wallet_activity_check_failed txHash={} error={}", txHash, e.getMessage());
        }
        try {
            consensusDetector.checkConsensus(trade, slug);
        } catch (Exception e) {
            log.warn("consensus_check_failed txHash={} error={}", txHash, e.getMessage());
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

        // 3. Not found — log structured WARN for Phase 7.5 audit, skip the trade.
        log.warn("event=wallet_trade_unknown_market conditionId={} proxyWallet={} txHash={} slug={} timestamp={}",
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
