package com.polysign.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polysign.model.WatchedWallet;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.io.InputStream;
import java.util.*;
import java.util.function.Supplier;

/**
 * Discovers whale wallets from the Polymarket leaderboard API at startup and
 * upserts them into the {@code watched_wallets} DynamoDB table.
 *
 * <p>Runs at order=2 — after {@link BootstrapRunner} (order=1) creates tables,
 * before {@link WalletBootstrap} (order=3) seeds the JSON fallback. Also
 * re-runs every 24 hours via {@link #refresh()} to pick up newly profitable traders.
 *
 * <p>Fetches top traders across 5 categories (OVERALL, POLITICS, SPORTS, CRYPTO,
 * FINANCE) using timePeriod=ALL for all-time rankings, deduplicates by proxy wallet
 * address, and upserts each wallet with a read-before-write guard: existing entries
 * with trade data ({@code lastSyncedAt} set by WalletPoller) are never overwritten.
 *
 * <p>If the leaderboard API is unreachable, falls back to
 * {@code watched_wallets.json} on the classpath.
 */
@Component
@Order(2)
public class WalletDiscovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WalletDiscovery.class);
    private static final String CB_NAME = "polymarket-data";

    // Leaderboard API max limit is 50 per request.
    // OVERALL=50 + 6×15=90 fetched across categories; after cross-category dedup ≈ 95-115 unique wallets.
    private static final List<CategorySpec> CATEGORIES = List.of(
            new CategorySpec("OVERALL",   50),
            new CategorySpec("POLITICS",  15),
            new CategorySpec("SPORTS",    15),
            new CategorySpec("CRYPTO",    15),
            new CategorySpec("FINANCE",   15),
            new CategorySpec("ECONOMICS", 15),
            new CategorySpec("TECH",      15)
    );

    private record CategorySpec(String name, int limit) {}

    private final WebClient                       dataApiClient;
    private final DynamoDbTable<WatchedWallet>    watchedWalletsTable;
    private final ObjectMapper                    mapper;
    private final CircuitBreaker                  circuitBreaker;
    private final Retry                           retry;

    public WalletDiscovery(
            @Qualifier("dataApiClient") WebClient dataApiClient,
            DynamoDbTable<WatchedWallet> watchedWalletsTable,
            ObjectMapper mapper,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry) {
        this.dataApiClient       = dataApiClient;
        this.watchedWalletsTable = watchedWalletsTable;
        this.mapper              = mapper;
        this.circuitBreaker      = cbRegistry.circuitBreaker(CB_NAME);
        this.retry               = retryRegistry.retry(CB_NAME);
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("WalletDiscovery starting — fetching Polymarket leaderboard");
        discover();
    }

    /** Re-runs every 24 hours (after an initial 24h delay) to pick up newly profitable traders. */
    @Scheduled(fixedDelay = 86_400_000L, initialDelay = 86_400_000L)
    public void refresh() {
        log.info("WalletDiscovery scheduled refresh — re-fetching Polymarket leaderboard");
        discover();
    }

    private void discover() {
        try {
            Map<String, WatchedWallet> wallets = fetchFromLeaderboard();
            if (wallets.isEmpty()) {
                log.warn("wallet_discovery_empty — falling back to watched_wallets.json");
                wallets = loadFromJson();
            }
            int seeded = upsertWallets(wallets.values());
            log.info("wallet_discovery_complete discovered={} new={} categories={}",
                    wallets.size(), seeded,
                    CATEGORIES.stream().map(CategorySpec::name).toList());
        } catch (Exception e) {
            log.warn("wallet_discovery_api_failed error={} — falling back to watched_wallets.json", e.getMessage());
            try {
                Map<String, WatchedWallet> fallback = loadFromJson();
                int seeded = upsertWallets(fallback.values());
                log.info("wallet_discovery_fallback_complete seeded={}", seeded);
            } catch (Exception e2) {
                log.error("wallet_discovery_fallback_failed error={}", e2.getMessage(), e2);
            }
        }
    }

    /**
     * Fetches top traders from all configured leaderboard categories.
     * Deduplicates by lowercase proxy wallet address (first category wins).
     */
    private Map<String, WatchedWallet> fetchFromLeaderboard() {
        LinkedHashMap<String, WatchedWallet> deduped = new LinkedHashMap<>();
        int totalDiscovered = 0;

        for (CategorySpec cat : CATEGORIES) {
            try {
                List<Map<String, Object>> entries = fetchCategory(cat.name(), cat.limit());
                for (Map<String, Object> entry : entries) {
                    totalDiscovered++;
                    String proxyWallet = stringOrNull(entry, "proxyWallet");
                    if (proxyWallet == null || proxyWallet.isBlank()) continue;

                    String address = proxyWallet.toLowerCase();
                    if (deduped.containsKey(address)) continue; // first category wins

                    String userName = stringOrNull(entry, "userName");
                    String alias = (userName != null && !userName.isBlank())
                            ? userName
                            : address.substring(0, 6) + "…" + address.substring(address.length() - 4);

                    WatchedWallet wallet = new WatchedWallet();
                    wallet.setAddress(address);
                    wallet.setAlias(alias);
                    wallet.setCategory(cat.name().toLowerCase());
                    wallet.setNotes("Auto-discovered from Polymarket leaderboard");
                    wallet.setQualityScore(initialQualityScore(entry));
                    deduped.put(address, wallet);
                }
                log.debug("wallet_discovery_category category={} fetched={}", cat.name(), entries.size());
            } catch (Exception e) {
                log.warn("wallet_discovery_category_failed category={} error={}", cat.name(), e.getMessage());
            }
        }

        log.info("wallet_discovery_fetched total={} after_dedup={}", totalDiscovered, deduped.size());
        return deduped;
    }

    private List<Map<String, Object>> fetchCategory(String category, int limit) {
        Supplier<List<Map<String, Object>>> call = () -> {
            String body = dataApiClient.get()
                    .uri(u -> u.path("/v1/leaderboard")
                            .queryParam("timePeriod", "ALL")
                            .queryParam("orderBy", "PNL")
                            .queryParam("limit", limit)
                            .queryParam("category", category)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            try {
                return mapper.readValue(body, new TypeReference<>() {});
            } catch (Exception e) {
                throw new RuntimeException("JSON parse failure for leaderboard category=" + category, e);
            }
        };
        return Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(circuitBreaker, call)).get();
    }

    /**
     * Falls back to the classpath watched_wallets.json seed file.
     */
    private Map<String, WatchedWallet> loadFromJson() {
        LinkedHashMap<String, WatchedWallet> result = new LinkedHashMap<>();
        try (InputStream is = getClass().getResourceAsStream("/watched_wallets.json")) {
            if (is == null) {
                log.warn("wallet_discovery_fallback_skipped reason=watched_wallets.json_not_found");
                return result;
            }
            List<Map<String, Object>> seeds = mapper.readValue(is, new TypeReference<>() {});
            for (Map<String, Object> seed : seeds) {
                String address = ((String) seed.getOrDefault("address", "")).toLowerCase();
                if (address.isBlank() || address.equals("_comment") || address.startsWith("0x0000")) continue;
                WatchedWallet wallet = new WatchedWallet();
                wallet.setAddress(address);
                wallet.setAlias((String) seed.get("alias"));
                wallet.setCategory((String) seed.get("category"));
                wallet.setNotes((String) seed.get("notes"));
                wallet.setQualityScore(3.0); // floor: log10(100) * 1.5 — no trade data in JSON
                result.put(address, wallet);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load watched_wallets.json", e);
        }
        return result;
    }

    /**
     * Upserts wallets with read-before-write: existing entries with trade data
     * (lastSyncedAt set by WalletPoller) are never overwritten.
     */
    private int upsertWallets(Collection<WatchedWallet> wallets) {
        int seeded = 0;
        int skipped = 0;
        for (WatchedWallet wallet : wallets) {
            try {
                WatchedWallet existing = watchedWalletsTable.getItem(
                        Key.builder().partitionValue(wallet.getAddress()).build());
                if (existing != null && existing.getLastSyncedAt() != null) {
                    skipped++;
                    continue;
                }
                watchedWalletsTable.putItem(wallet);
                seeded++;
            } catch (Exception e) {
                log.warn("wallet_discovery_upsert_failed address={} error={}",
                        wallet.getAddress(), e.getMessage());
            }
        }
        log.debug("wallet_discovery_upsert seeded={} skipped={}", seeded, skipped);
        return seeded;
    }

    private static String stringOrNull(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    /**
     * Estimates an initial quality score from a leaderboard API entry.
     * Mirrors the WalletPoller formula: log10(max(avgTradeSize, 100)) * 1.5,
     * using a floor of 100 so newly discovered wallets start at 3.0 minimum
     * rather than contributing zero to the consensus quality-sum gate.
     *
     * <p>Falls back to 3.0 if {@code volume} or {@code numTrades} fields are
     * absent or unparseable — this is expected for some leaderboard responses.
     */
    private static double initialQualityScore(Map<String, Object> entry) {
        try {
            Object volObj    = entry.get("volume");
            Object tradesObj = entry.get("numTrades");
            if (volObj != null && tradesObj != null) {
                double vol    = Double.parseDouble(volObj.toString());
                double trades = Double.parseDouble(tradesObj.toString());
                if (trades > 0 && vol > 0) {
                    return Math.log10(Math.max(vol / trades, 100.0)) * 1.5;
                }
            }
        } catch (Exception ignored) {}
        return 3.0; // log10(100) * 1.5 — $100 avg trade floor
    }
}
