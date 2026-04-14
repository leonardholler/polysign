package com.polysign.wallet;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polysign.common.AppClock;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Fetches and caches lifetime wallet activity metadata from the Polymarket Data API.
 *
 * <p>Cache strategy: DynamoDB with a 6-hour TTL. On cache miss, calls
 * {@code /activity?user={address}&limit=500}. If the response has fewer than 500
 * items, we have the full history. If exactly 500, we make a second call with
 * {@code sortDirection=ASC&limit=1} to find the oldest trade.
 *
 * <p>On any exception, returns a sentinel {@link WalletMetadata} with
 * {@code dataUnavailable=true}. Failures are never cached.
 */
@Service
public class WalletMetadataService {

    private static final Logger log = LoggerFactory.getLogger(WalletMetadataService.class);
    private static final String CB_NAME         = "polymarket-data";
    private static final String RATE_LIMITER    = "polymarket-data-metadata";
    private static final int    ACTIVITY_LIMIT  = 500;
    private static final long   CACHE_TTL_SECS  = Duration.ofHours(6).getSeconds();

    private final DynamoDbTable<WalletMetadata> walletMetadataTable;
    private final WebClient                      dataApiClient;
    private final AppClock                       clock;
    private final CircuitBreaker                 circuitBreaker;
    private final Retry                          retry;
    private final RateLimiter                    rateLimiter;
    private final ObjectMapper                   mapper;

    public WalletMetadataService(
            DynamoDbTable<WalletMetadata> walletMetadataTable,
            @Qualifier("dataApiClient") WebClient dataApiClient,
            AppClock clock,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry,
            RateLimiterRegistry rlRegistry,
            ObjectMapper mapper) {
        this.walletMetadataTable = walletMetadataTable;
        this.dataApiClient       = dataApiClient;
        this.clock               = clock;
        this.circuitBreaker      = cbRegistry.circuitBreaker(CB_NAME);
        this.retry               = retryRegistry.retry(CB_NAME);
        this.rateLimiter         = rlRegistry.rateLimiter(RATE_LIMITER);
        this.mapper              = mapper;
    }

    /**
     * Returns cached or freshly fetched metadata for the given wallet address.
     *
     * <p>The address is lowercased before cache lookup and API call.
     * On any error, returns a sentinel with {@code dataUnavailable=true}.
     */
    public WalletMetadata get(String address) {
        String normalised = address.toLowerCase();

        // 1. DynamoDB cache read (no rate-limit; local)
        try {
            WalletMetadata cached = walletMetadataTable.getItem(
                    Key.builder().partitionValue(normalised).build());
            if (cached != null) {
                long nowEpoch = clock.now().getEpochSecond();
                if (cached.getExpiresAt() != null && cached.getExpiresAt() > nowEpoch) {
                    log.debug("wallet_metadata_cache_hit address={}", normalised);
                    return cached;
                }
            }
        } catch (Exception e) {
            log.warn("wallet_metadata_cache_read_failed address={} error={}", normalised, e.getMessage());
        }

        // 2. API fetch
        try {
            return fetchAndCache(normalised);
        } catch (Exception e) {
            log.warn("wallet_metadata_fetch_failed address={} error={}", normalised, e.getMessage());
            WalletMetadata sentinel = new WalletMetadata();
            sentinel.setAddress(normalised);
            sentinel.setDataUnavailable(true);
            return sentinel;
        }
    }

    // ── Internal fetch ────────────────────────────────────────────────────────

    private WalletMetadata fetchAndCache(String address) {
        // First call: most recent 500 trades, DESC (default)
        List<Map<String, Object>> recent = callActivity(address, ACTIVITY_LIMIT, null);

        List<Map<String, Object>> trades = recent.stream()
                .filter(item -> "TRADE".equals(item.get("type")))
                .toList();

        String firstTradeAt;
        int    count;
        BigDecimal volume = BigDecimal.ZERO;

        for (Map<String, Object> item : trades) {
            Object us = item.get("usdcSize");
            if (us != null) {
                try {
                    volume = volume.add(new BigDecimal(us.toString()));
                } catch (NumberFormatException ignored) {}
            }
        }
        volume = volume.setScale(2, RoundingMode.HALF_UP);

        if (trades.size() < ACTIVITY_LIMIT) {
            // We have the full history
            count = trades.size();
            firstTradeAt = trades.stream()
                    .map(t -> {
                        Object ts = t.get("timestamp");
                        return ts != null ? parseLong(ts.toString()) : 0L;
                    })
                    .min(Comparator.naturalOrder())
                    .map(epoch -> java.time.Instant.ofEpochSecond(epoch).toString())
                    .orElse(null);
        } else {
            // Exactly 500 — there may be more; fetch oldest for firstTradeAt
            count = ACTIVITY_LIMIT; // 500+
            List<Map<String, Object>> oldest = callActivity(address, 1, "ASC");
            List<Map<String, Object>> oldestTrades = oldest.stream()
                    .filter(item -> "TRADE".equals(item.get("type")))
                    .toList();
            if (!oldestTrades.isEmpty()) {
                Object ts = oldestTrades.get(0).get("timestamp");
                firstTradeAt = ts != null
                        ? java.time.Instant.ofEpochSecond(parseLong(ts.toString())).toString()
                        : null;
            } else {
                firstTradeAt = null;
            }
        }

        WalletMetadata meta = new WalletMetadata();
        meta.setAddress(address);
        meta.setFirstTradeAt(firstTradeAt);
        meta.setLifetimeTradeCount(count);
        meta.setLifetimeVolumeUsd(volume);
        meta.setExpiresAt(clock.now().getEpochSecond() + CACHE_TTL_SECS);

        try {
            walletMetadataTable.putItem(meta);
            log.debug("wallet_metadata_cached address={} count={} volume={}", address, count, volume);
        } catch (Exception e) {
            log.warn("wallet_metadata_cache_write_failed address={} error={}", address, e.getMessage());
        }

        return meta;
    }

    private List<Map<String, Object>> callActivity(String address, int limit, String sortDirection) {
        Supplier<List<Map<String, Object>>> call = () -> {
            String body = dataApiClient.get()
                    .uri(u -> {
                        var b = u.path("/activity")
                                 .queryParam("user",  address)
                                 .queryParam("limit", limit)
                                 .queryParam("type",  "TRADE");
                        if (sortDirection != null) {
                            b = b.queryParam("sortDirection", sortDirection);
                        }
                        return b.build();
                    })
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            try {
                return mapper.readValue(body, new TypeReference<>() {});
            } catch (Exception e) {
                throw new RuntimeException("JSON parse failure fetching activity for " + address, e);
            }
        };

        return RateLimiter.decorateSupplier(rateLimiter,
               Retry.decorateSupplier(retry,
               CircuitBreaker.decorateSupplier(circuitBreaker, call))).get();
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
    }
}
