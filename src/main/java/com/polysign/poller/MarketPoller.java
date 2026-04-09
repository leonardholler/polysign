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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Polls the Polymarket Gamma API for active markets and upserts them into DynamoDB.
 *
 * <p>Pre-upsert filter pipeline (applied in order, cheapest first):
 * <ol>
 *   <li>No market ID → skip</li>
 *   <li>Lifetime volume &lt; {@code min-volume-usdc} (default 10 000) → skip</li>
 *   <li>24-hour volume &lt; {@code min-volume-24h-usdc} (default 5 000) → skip</li>
 *   <li>End-date within {@code min-hours-to-end} hours (default 6) → skip</li>
 * </ol>
 *
 * <p>Each cycle emits one INFO summary:
 * {@code market_poll_complete kept=X of=Y skip_lifetime=A skip_24h=B skip_eol=C}
 */
@Component
public class MarketPoller {

    private static final Logger log = LoggerFactory.getLogger(MarketPoller.class);

    private static final int    PAGE_LIMIT = 200;
    private static final String CB_NAME    = "polymarket-gamma";

    // ── Filter decision codes returned by upsertMarket() ─────────────────────
    private enum FilterResult { KEPT, SKIP_NO_ID, SKIP_LIFETIME, SKIP_24H, SKIP_EOL }

    // ── Configuration ─────────────────────────────────────────────────────────
    private final double minVolumeUsdc;
    private final double minVolume24hUsdc;
    private final long   minHoursToEnd;

    // ── Dependencies ──────────────────────────────────────────────────────────
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
            MeterRegistry meterRegistry,
            @Value("${polysign.pollers.market.min-volume-usdc:10000}")    double minVolumeUsdc,
            @Value("${polysign.pollers.market.min-volume-24h-usdc:5000}") double minVolume24hUsdc,
            @Value("${polysign.pollers.market.min-hours-to-end:6}")       long   minHoursToEnd) {

        this.minVolumeUsdc    = minVolumeUsdc;
        this.minVolume24hUsdc = minVolume24hUsdc;
        this.minHoursToEnd    = minHoursToEnd;
        this.gammaClient      = gammaClient;
        this.marketsTable     = marketsTable;
        this.clock            = clock;
        this.mapper           = mapper;
        this.circuitBreaker   = cbRegistry.circuitBreaker(CB_NAME);
        this.retry            = retryRegistry.retry(CB_NAME);

        Gauge.builder("polysign.markets.tracked", trackedCount, AtomicLong::get)
             .description("Number of active markets kept after all filters in the last poll cycle")
             .register(meterRegistry);
    }

    @Scheduled(
        fixedDelayString   = "${polysign.pollers.market.interval-ms:60000}",
        initialDelayString = "${polysign.pollers.market.initial-delay-ms:5000}"
    )
    public void pollMarkets() {
        try (var ignored = CorrelationId.set()) {
            log.info("market_poll_start");

            int total       = 0;
            int kept        = 0;
            int skipLifetime = 0;
            int skip24h     = 0;
            int skipEol     = 0;
            int offset      = 0;

            while (true) {
                final int currentOffset = offset;
                List<Map<String, Object>> page;
                try {
                    page = fetchPage(currentOffset);
                } catch (Exception e) {
                    log.error("market_page_fetch_failed offset={} error={}", currentOffset, e.getMessage(), e);
                    break;
                }
                if (page.isEmpty()) break;

                for (Map<String, Object> item : page) {
                    total++;
                    try {
                        switch (upsertMarket(item)) {
                            case KEPT         -> kept++;
                            case SKIP_LIFETIME -> skipLifetime++;
                            case SKIP_24H     -> skip24h++;
                            case SKIP_EOL     -> skipEol++;
                            case SKIP_NO_ID   -> { /* no-op: malformed item, not worth counting */ }
                        }
                    } catch (Exception e) {
                        log.warn("market_item_error marketId={} error={}",
                                 item.getOrDefault("id", "unknown"), e.getMessage(), e);
                    }
                }

                if (page.size() < PAGE_LIMIT) break;
                offset += PAGE_LIMIT;
            }

            trackedCount.set(kept);
            log.info("market_poll_complete kept={} of={} skip_lifetime={} skip_24h={} skip_eol={}",
                     kept, total, skipLifetime, skip24h, skipEol);

        } catch (Exception e) {
            log.error("market_poll_failed error={}", e.getMessage(), e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

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
     * Applies the filter pipeline, then upserts the market if all checks pass.
     * Filters are evaluated cheapest-first to minimise work for the common reject path.
     *
     * @return a {@link FilterResult} indicating disposition (never throws)
     */
    private FilterResult upsertMarket(Map<String, Object> item) {
        String marketId = stringOrNull(item, "id");
        if (marketId == null || marketId.isBlank()) return FilterResult.SKIP_NO_ID;

        // ── 1. Lifetime volume floor ──────────────────────────────────────────
        String lifetimeVolumeStr = stringOrNull(item, "volume");
        if (lifetimeVolumeStr != null) {
            try {
                if (Double.parseDouble(lifetimeVolumeStr) < minVolumeUsdc) {
                    log.debug("market_skip_lifetime marketId={} volume={}", marketId, lifetimeVolumeStr);
                    return FilterResult.SKIP_LIFETIME;
                }
            } catch (NumberFormatException ignored) { /* let through */ }
        }

        // ── 2. 24-hour volume floor ───────────────────────────────────────────
        String volume24hStr = stringOrNull(item, "volume24hr"); // Gamma field name
        if (volume24hStr != null) {
            try {
                if (Double.parseDouble(volume24hStr) < minVolume24hUsdc) {
                    log.debug("market_skip_24h marketId={} volume24h={}", marketId, volume24hStr);
                    return FilterResult.SKIP_24H;
                }
            } catch (NumberFormatException ignored) { /* let through */ }
        }

        // ── 3. End-of-life filter ─────────────────────────────────────────────
        String endDateStr = stringOrNull(item, "endDate");
        if (endDateStr != null) {
            try {
                Instant endDate = Instant.parse(endDateStr);
                Instant cutoff  = clock.now().plus(minHoursToEnd, ChronoUnit.HOURS);
                if (endDate.isBefore(cutoff)) {
                    log.debug("market_skip_eol marketId={} endDate={}", marketId, endDateStr);
                    return FilterResult.SKIP_EOL;
                }
            } catch (DateTimeParseException e) {
                // Non-standard date format — let the market through rather than incorrectly filtering.
                log.debug("market_end_date_unparseable marketId={} endDate={}", marketId, endDateStr);
            }
        }

        // ── All filters passed: build and persist ─────────────────────────────
        List<String> outcomeList   = parseJsonStringList(item, "outcomes");
        List<String> clobTokenList = parseJsonStringList(item, "clobTokenIds");
        String yesTokenId = clobTokenList.isEmpty() ? null : clobTokenList.get(0);

        String question  = stringOrNull(item, "question");
        String eventSlug = stringOrNull(item, "slug");
        String category  = CategoryClassifier.classify(question, eventSlug);
        if (CategoryClassifier.OTHER.equals(category)) {
            log.info("market_category_other marketId={} question={}", marketId, question);
        }

        Set<String> keywords = extractKeywords(question);

        Key key = Key.builder().partitionValue(marketId).build();
        Market existing = marketsTable.getItem(key);
        Boolean isWatched = (existing != null && existing.getIsWatched() != null)
                            ? existing.getIsWatched() : Boolean.FALSE;

        Market market = new Market();
        market.setMarketId(marketId);
        market.setQuestion(question);
        market.setCategory(category);
        market.setEndDate(endDateStr);
        market.setVolume(lifetimeVolumeStr);
        market.setVolume24h(volume24hStr);
        market.setOutcomes(outcomeList);
        market.setKeywords(keywords);
        market.setIsWatched(isWatched);
        market.setUpdatedAt(clock.nowIso());
        market.setYesTokenId(yesTokenId);
        market.setClobTokenIds(stringOrNull(item, "clobTokenIds"));

        marketsTable.putItem(market);
        log.debug("market_upserted marketId={} category={}", marketId, category);
        return FilterResult.KEPT;
    }

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
