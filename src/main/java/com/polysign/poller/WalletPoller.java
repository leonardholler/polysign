package com.polysign.poller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polysign.common.AppClock;
import com.polysign.common.AppStats;
import com.polysign.common.CorrelationId;
import com.polysign.model.Market;
import com.polysign.model.WalletTrade;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Polls the Polymarket Data API for trades by active high-volume markets and writes
 * new trades to the {@code wallet_trades} DynamoDB table.
 *
 * <h3>Poll strategy</h3>
 * Scans the {@code markets} table for markets with volume24h ≥ minMarketVolume24h.
 * For each qualifying market, calls the Data API {@code /trades?market={conditionId}&startTime={cursor}&limit=100}.
 * Per-market cursors (epoch seconds) are kept in {@code marketCursors} to avoid re-fetching.
 *
 * <h3>Idempotency</h3>
 * {@code wallet_trades} PK=address, SK=txHash. PutItem overwrites on the same
 * (address, txHash) — writing the same trade twice is a no-op (identical data).
 */
@Component
public class WalletPoller {

    private static final Logger log = LoggerFactory.getLogger(WalletPoller.class);
    private static final String CB_NAME        = "polymarket-data";
    private static final int    PAGE_SIZE      = 100;
    private static final int    STALL_THRESHOLD = 3;

    private final double minMarketVolume24h;

    /** Per-market poll cursors: conditionId → last seen epoch second. */
    private final ConcurrentHashMap<String, Long> marketCursors = new ConcurrentHashMap<>();

    /** Consecutive zero-trade cycles before PIPELINE STALL warning. */
    private int consecutiveZeroCycles = 0;

    /** Guards the one-time raw API response sample log. */
    private volatile boolean firstRawTradeLogged = false;

    private final WebClient                  dataApiClient;
    private final DynamoDbTable<Market>      marketsTable;
    private final DynamoDbTable<WalletTrade> walletTradesTable;
    private final AppClock                   clock;
    private final AppStats                   appStats;
    private final ObjectMapper               mapper;
    private final CircuitBreaker             circuitBreaker;
    private final Retry                      retry;
    private final Counter                    tradesIngested;

    public WalletPoller(
            @Qualifier("dataApiClient") WebClient dataApiClient,
            DynamoDbTable<Market> marketsTable,
            DynamoDbTable<WalletTrade> walletTradesTable,
            AppClock clock,
            AppStats appStats,
            ObjectMapper mapper,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry,
            MeterRegistry meterRegistry,
            @Value("${polysign.pollers.wallet.min-market-volume-24h:100000}") double minMarketVolume24h) {

        this.minMarketVolume24h = minMarketVolume24h;
        this.dataApiClient      = dataApiClient;
        this.marketsTable       = marketsTable;
        this.walletTradesTable  = walletTradesTable;
        this.clock              = clock;
        this.appStats           = appStats;
        this.mapper             = mapper;
        this.circuitBreaker     = cbRegistry.circuitBreaker(CB_NAME);
        this.retry              = retryRegistry.retry(CB_NAME);

        this.tradesIngested = Counter.builder("polysign.wallet.trades.ingested")
                .description("Total wallet trades written to wallet_trades table")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString   = "${polysign.pollers.wallet.interval-ms:60000}",
               initialDelayString = "${polysign.pollers.wallet.initial-delay-ms:90000}")
    public void poll() {
        try (var ignored = CorrelationId.set()) {
            Instant now = clock.now();
            int totalTrades = 0;
            int marketsPolled = 0;

            for (Market market : marketsTable.scan().items()) {
                double vol = parseDouble(market.getVolume24h());
                if (vol < minMarketVolume24h) continue;
                String conditionId = market.getConditionId();
                if (conditionId == null || conditionId.isBlank()) continue;

                marketsPolled++;
                long cursor = marketCursors.getOrDefault(
                        conditionId, now.minusSeconds(90).getEpochSecond());

                try {
                    List<Map<String, Object>> trades = fetchTradesByMarket(conditionId, cursor);
                    int wrote = 0;
                    long maxTs = cursor;

                    for (Map<String, Object> raw : trades) {
                        try {
                            writeTrade(raw, market);
                            wrote++;
                            Object tsObj = raw.get("timestamp");
                            if (tsObj != null) {
                                try {
                                    long ts = Long.parseLong(tsObj.toString());
                                    if (ts > maxTs) maxTs = ts;
                                } catch (NumberFormatException ignored2) {}
                            }
                        } catch (Exception e) {
                            log.warn("wallet_trade_write_failed conditionId={} txHash={} error={}",
                                    conditionId, raw.getOrDefault("transactionHash", "?"), e.getMessage());
                        }
                    }

                    // Advance cursor past the last trade seen (or to now if no trades)
                    marketCursors.put(conditionId,
                            trades.isEmpty() ? now.getEpochSecond() : maxTs + 1);

                    totalTrades += wrote;
                    if (wrote > 0) tradesIngested.increment(wrote);
                } catch (Exception e) {
                    log.warn("wallet_market_poll_failed conditionId={} error={}",
                            conditionId, e.getMessage());
                }
            }

            if (totalTrades > 500) {
                log.warn("wallet_poll_high_volume totalTrades={} — consider reducing poll interval", totalTrades);
            }

            log.info("wallet_poll_complete marketsPolled={} trades_written={}", marketsPolled, totalTrades);

            // Pipeline stall detection
            if (totalTrades == 0) {
                consecutiveZeroCycles++;
                if (consecutiveZeroCycles >= STALL_THRESHOLD) {
                    log.error("PIPELINE STALL: No wallet trades ingested in {} consecutive cycles",
                            consecutiveZeroCycles);
                }
            } else {
                consecutiveZeroCycles = 0;
            }
        } catch (Exception e) {
            log.error("wallet_poll_error error={}", e.getMessage(), e);
        }
    }

    // ── Trade writing ─────────────────────────────────────────────────────────

    private void writeTrade(Map<String, Object> raw, Market market) {
        if (!firstRawTradeLogged) {
            firstRawTradeLogged = true;
            log.info("wallet_raw_trade_sample conditionId={} fields={}",
                    market.getConditionId(), raw);
        }

        String txHash      = str(raw, "transactionHash");
        String proxyWallet = str(raw, "proxyWallet");
        Object tsObj       = raw.get("timestamp");

        if (txHash == null || txHash.isBlank() || proxyWallet == null || tsObj == null) {
            log.debug("wallet_trade_malformed txHash={} proxyWallet={}", txHash, proxyWallet);
            return;
        }

        String address = proxyWallet.toLowerCase();
        long epochSeconds = Long.parseLong(tsObj.toString());
        String isoTimestamp = Instant.ofEpochSecond(epochSeconds).toString();

        // sizeUsdc = size × price (Data API returns shares, not USDC value)
        BigDecimal size  = decimal(raw, "size");
        BigDecimal price = decimal(raw, "price");
        BigDecimal sizeUsdc = (size != null && price != null)
                ? size.multiply(price).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        WalletTrade trade = new WalletTrade();
        trade.setAddress(address);
        trade.setTxHash(txHash);
        trade.setTimestamp(isoTimestamp);
        trade.setMarketId(market.getMarketId());
        trade.setMarketQuestion(str(raw, "title"));
        trade.setSide(str(raw, "side"));
        trade.setOutcome(str(raw, "outcome"));
        trade.setSizeUsdc(sizeUsdc);
        trade.setPrice(price);
        trade.setSlug(str(raw, "slug"));

        walletTradesTable.putItem(trade);
        appStats.recordTrade(address);
        log.debug("wallet_trade_written address={} txHash={} marketId={} sizeUsdc={}",
                address, txHash, market.getMarketId(), sizeUsdc);
    }

    // ── API call ──────────────────────────────────────────────────────────────

    private List<Map<String, Object>> fetchTradesByMarket(String conditionId, long startEpochSec) {
        Supplier<List<Map<String, Object>>> call = () -> {
            String body = dataApiClient.get()
                    .uri(u -> u.path("/trades")
                               .queryParam("market",    conditionId)
                               .queryParam("startTime", startEpochSec)
                               .queryParam("limit",     PAGE_SIZE)
                               .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            try {
                return mapper.readValue(body, new TypeReference<>() {});
            } catch (Exception e) {
                throw new RuntimeException("JSON parse failure fetching trades for " + conditionId, e);
            }
        };
        return Retry.decorateSupplier(retry,
               CircuitBreaker.decorateSupplier(circuitBreaker, call)).get();
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

    private static double parseDouble(String s) {
        if (s == null) return 0.0;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0.0; }
    }
}
