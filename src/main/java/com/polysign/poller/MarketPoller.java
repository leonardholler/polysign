package com.polysign.poller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polysign.common.AppClock;
import com.polysign.common.CategoryClassifier;
import com.polysign.common.CorrelationId;
import com.polysign.model.Market;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Polls the Polymarket Gamma API for active markets and upserts them into DynamoDB.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Paginate through all active / non-closed markets (200 per page via limit+offset)</li>
 *   <li>Parse JSON-string-encoded list fields: outcomes, clobTokenIds</li>
 *   <li>Pre-extract yesTokenId (clobTokenIds[0]) so PricePoller never re-parses JSON</li>
 *   <li>Classify market category via {@link CategoryClassifier}; log INFO for "other"</li>
 *   <li>Preserve isWatched flag with a read-before-write DynamoDB get</li>
 *   <li>Wrap every Gamma API call in Resilience4j CircuitBreaker + Retry</li>
 *   <li>Per-item catch — one bad market never crashes the scheduler loop</li>
 * </ul>
 */
@Component
public class MarketPoller {

    private static final Logger log = LoggerFactory.getLogger(MarketPoller.class);

    private static final int    PAGE_LIMIT    = 200;
    private static final String CB_NAME       = "polymarket-gamma";

    private final WebClient             gammaClient;
    private final DynamoDbTable<Market> marketsTable;
    private final AppClock              clock;
    private final ObjectMapper          mapper;
    private final CircuitBreaker        circuitBreaker;
    private final Retry                 retry;
    private final AtomicLong            trackedCount = new AtomicLong(0);

    public MarketPoller(
            @Qualifier("gammaApiClient") WebClient gammaClient,
            DynamoDbTable<Market> marketsTable,
            AppClock clock,
            ObjectMapper mapper,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry,
            MeterRegistry meterRegistry) {

        this.gammaClient    = gammaClient;
        this.marketsTable   = marketsTable;
        this.clock          = clock;
        this.mapper         = mapper;
        this.circuitBreaker = cbRegistry.circuitBreaker(CB_NAME);
        this.retry          = retryRegistry.retry(CB_NAME);

        Gauge.builder("polysign.markets.tracked", trackedCount, AtomicLong::get)
             .description("Number of active markets processed in the last poll cycle")
             .register(meterRegistry);
    }

    @Scheduled(
        fixedDelayString   = "${polysign.pollers.market.interval-ms:60000}",
        initialDelayString = "${polysign.pollers.market.initial-delay-ms:5000}"
    )
    public void pollMarkets() {
        try (var ignored = CorrelationId.set()) {
            log.info("market_poll_start");

            int processed = 0;
            int offset    = 0;

            while (true) {
                final int currentOffset = offset;
                List<Map<String, Object>> page;

                try {
                    page = fetchPage(currentOffset);
                } catch (Exception e) {
                    // Circuit may be open — abort pagination, retry on next scheduled cycle.
                    log.error("market_page_fetch_failed offset={} error={}", currentOffset, e.getMessage(), e);
                    break;
                }

                if (page.isEmpty()) break;

                for (Map<String, Object> item : page) {
                    try {
                        upsertMarket(item);
                        processed++;
                    } catch (Exception e) {
                        log.warn("market_item_error marketId={} error={}",
                                 item.getOrDefault("id", "unknown"), e.getMessage(), e);
                    }
                }

                // If we received fewer items than PAGE_LIMIT, this was the last page.
                if (page.size() < PAGE_LIMIT) break;
                offset += PAGE_LIMIT;
            }

            trackedCount.set(processed);
            log.info("market_poll_complete processed={}", processed);

        } catch (Exception e) {
            log.error("market_poll_failed error={}", e.getMessage(), e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Fetches one page from the Gamma API, wrapped in CircuitBreaker + Retry.
     * The inner lambda is a pure supplier — no state captured beyond {@code offset}.
     */
    private List<Map<String, Object>> fetchPage(int offset) {
        Supplier<List<Map<String, Object>>> call = () -> {
            String body = gammaClient.get()
                .uri(u -> u.path("/markets")
                           .queryParam("active", "true")
                           .queryParam("closed", "false")
                           .queryParam("limit",  PAGE_LIMIT)
                           .queryParam("offset", offset)
                           .build())
                .retrieve()
                .bodyToMono(String.class)
                .block();

            try {
                return mapper.readValue(body, new TypeReference<>() {});
            } catch (Exception e) {
                throw new RuntimeException("JSON parse failure for Gamma page offset=" + offset, e);
            }
        };

        return Retry.decorateSupplier(retry,
               CircuitBreaker.decorateSupplier(circuitBreaker, call)).get();
    }

    /**
     * Builds a {@link Market} from a raw Gamma API item map and upserts it into DynamoDB.
     * Preserves the {@code isWatched} flag via read-before-write.
     */
    private void upsertMarket(Map<String, Object> item) {
        String marketId = stringOrNull(item, "id");
        if (marketId == null || marketId.isBlank()) {
            log.debug("market_item_no_id skipped");
            return;
        }

        // ── Parse JSON-string-encoded list fields ─────────────────────────────
        List<String> outcomeList   = parseJsonStringList(item, "outcomes");
        List<String> clobTokenList = parseJsonStringList(item, "clobTokenIds");

        // clobTokenIds[0] = YES token; pre-extracted so PricePoller skips JSON re-parse.
        String yesTokenId = clobTokenList.isEmpty() ? null : clobTokenList.get(0);

        // ── Category classification ───────────────────────────────────────────
        String question  = stringOrNull(item, "question");
        String eventSlug = stringOrNull(item, "slug");
        String category  = CategoryClassifier.classify(question, eventSlug);

        if (CategoryClassifier.OTHER.equals(category)) {
            log.info("market_category_other marketId={} question={}", marketId, question);
        }

        // ── Keyword extraction ────────────────────────────────────────────────
        Set<String> keywords = extractKeywords(question);

        // ── Preserve isWatched via read-before-write ──────────────────────────
        Key key = Key.builder().partitionValue(marketId).build();
        Market existing  = marketsTable.getItem(key);
        Boolean isWatched = (existing != null && existing.getIsWatched() != null)
                            ? existing.getIsWatched()
                            : Boolean.FALSE;

        // ── Build and persist ─────────────────────────────────────────────────
        Market market = new Market();
        market.setMarketId(marketId);
        market.setQuestion(question);
        market.setCategory(category);
        market.setEndDate(stringOrNull(item, "endDate"));
        market.setVolume(stringOrNull(item, "volume"));
        market.setVolume24h(stringOrNull(item, "volume24hr")); // Gamma field name is volume24hr
        market.setOutcomes(outcomeList);
        market.setKeywords(keywords);
        market.setIsWatched(isWatched);
        market.setUpdatedAt(clock.nowIso());
        market.setYesTokenId(yesTokenId);
        market.setClobTokenIds(stringOrNull(item, "clobTokenIds")); // raw JSON string preserved

        marketsTable.putItem(market);
        log.debug("market_upserted marketId={} category={} yesTokenId={}", marketId, category, yesTokenId);
    }

    /**
     * Parses a Gamma API field whose JSON value is itself a JSON-encoded array string.
     * e.g. {@code "outcomes": "[\"Yes\",\"No\"]"}.
     * Returns an empty (immutable) list if the field is absent, null, or unparseable.
     */
    private List<String> parseJsonStringList(Map<String, Object> item, String fieldName) {
        Object raw = item.get(fieldName);
        if (raw == null) return List.of();
        String s = raw.toString().trim();
        if (s.isBlank() || "null".equals(s)) return List.of();
        try {
            return mapper.readValue(s, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("json_string_list_parse_failed field={} value={}", fieldName, s);
            return List.of();
        }
    }

    private static String stringOrNull(Map<String, Object> item, String key) {
        Object v = item.get(key);
        return v == null ? null : v.toString();
    }

    /**
     * Tokenises the market question into lowercase, de-stop-worded keywords (length >= 3).
     * Used for news–market correlation in Phase 5.
     */
    private static final Set<String> STOP_WORDS = Set.of(
        "a","an","the","and","or","but","in","on","at","to","for","of","with","by",
        "from","is","are","will","would","could","should","who","what","when","where",
        "which","that","this","be","been","being","have","has","had","do","does","did",
        "not","no","if","as","it","its","we","you","he","she","they","their","there",
        "than","then","was","were","more","most","any","all","just","over","before",
        "after","between","during","up","down","into","out","how","many","much","per"
    );

    private static Set<String> extractKeywords(String question) {
        if (question == null || question.isBlank()) return Set.of();
        Set<String> keywords = new HashSet<>();
        for (String token : question.toLowerCase().split("[^a-z0-9]+")) {
            if (token.length() >= 3 && !STOP_WORDS.contains(token)) {
                keywords.add(token);
            }
        }
        return keywords;
    }
}
