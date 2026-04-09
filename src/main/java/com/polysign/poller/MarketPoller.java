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

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Polls the Polymarket Gamma API for active markets and upserts them into DynamoDB.
 *
 * <p>Each cycle runs a two-phase pipeline:
 *
 * <p><b>Phase 1 — Quality gates</b> (applied per item, cheapest first):
 * <ol>
 *   <li>Lifetime volume &lt; {@code min-volume-usdc} → skip (is this market real?)</li>
 *   <li>24-hour volume &lt; {@code min-volume-24h-usdc} → skip (is it actively trading?)</li>
 *   <li>End-date within {@code min-hours-to-end} hours → skip (too close to expiry)</li>
 * </ol>
 *
 * <p><b>Phase 2 — Scale gate</b> (applied to the quality-passed set):
 * <ol>
 *   <li>Sort descending by 24h volume; tiebreak descending by lifetime volume</li>
 *   <li>Take the top {@code max-markets} (default 400)</li>
 * </ol>
 *
 * <p>Cap is applied AFTER quality gates so the final count is always
 * {@code min(passed, max-markets)}, never inflated by pre-cap filtering.
 *
 * <p>Each cycle emits one INFO summary line:
 * {@code market_poll_complete of=N kept_after_filters=X kept_after_cap=Y
 * cutoff_volume24hr=Z skip_lifetime=A skip_24h=B skip_eol=C}
 */
@Component
public class MarketPoller {

    private static final Logger log = LoggerFactory.getLogger(MarketPoller.class);

    private static final int    PAGE_LIMIT = 200;
    private static final String CB_NAME    = "polymarket-gamma";

    /**
     * Holds a market that has passed all quality gates, along with its parsed
     * volume values for sorting. The raw API map is kept so {@link #doUpsert}
     * can extract all fields without re-fetching.
     */
    private record Candidate(Map<String, Object> raw, double volume24h, double volumeLifetime) {}

    // ── Configuration ─────────────────────────────────────────────────────────
    private final double minVolumeUsdc;
    private final double minVolume24hUsdc;
    private final long   minHoursToEnd;
    private final int    maxMarkets;

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
            @Value("${polysign.pollers.market.min-volume-24h-usdc:10000}") double minVolume24hUsdc,
            @Value("${polysign.pollers.market.min-hours-to-end:12}")      long   minHoursToEnd,
            @Value("${polysign.pollers.market.max-markets:400}")          int    maxMarkets) {

        this.minVolumeUsdc    = minVolumeUsdc;
        this.minVolume24hUsdc = minVolume24hUsdc;
        this.minHoursToEnd    = minHoursToEnd;
        this.maxMarkets       = maxMarkets;
        this.gammaClient      = gammaClient;
        this.marketsTable     = marketsTable;
        this.clock            = clock;
        this.mapper           = mapper;
        this.circuitBreaker   = cbRegistry.circuitBreaker(CB_NAME);
        this.retry            = retryRegistry.retry(CB_NAME);

        Gauge.builder("polysign.markets.tracked", trackedCount, AtomicLong::get)
             .description("Active markets kept after all quality + scale gates in the last poll cycle")
             .register(meterRegistry);
    }

    @Scheduled(
        fixedDelayString   = "${polysign.pollers.market.interval-ms:60000}",
        initialDelayString = "${polysign.pollers.market.initial-delay-ms:5000}"
    )
    public void pollMarkets() {
        try (var ignored = CorrelationId.set()) {
            log.info("market_poll_start");

            // ── Phase 1: Fetch all pages + apply quality gates ────────────────
            List<Candidate> candidates = new ArrayList<>();
            int total        = 0;
            int skipLifetime = 0;
            int skip24h      = 0;
            int skipEol      = 0;
            int offset       = 0;

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
                        String marketId = stringOrNull(item, "id");
                        if (marketId == null || marketId.isBlank()) continue; // malformed; don't count

                        // ── Quality gate 1: lifetime volume ───────────────────
                        Double vol = parseDouble(item, "volume");
                        if (vol != null && vol < minVolumeUsdc) {
                            log.debug("market_skip_lifetime marketId={} volume={}", marketId, vol);
                            skipLifetime++;
                            continue;
                        }

                        // ── Quality gate 2: 24-hour volume ────────────────────
                        Double vol24h = parseDouble(item, "volume24hr");
                        if (vol24h != null && vol24h < minVolume24hUsdc) {
                            log.debug("market_skip_24h marketId={} volume24h={}", marketId, vol24h);
                            skip24h++;
                            continue;
                        }

                        // ── Quality gate 3: end-of-life ───────────────────────
                        String endDateStr = stringOrNull(item, "endDate");
                        if (endDateStr != null) {
                            try {
                                if (Instant.parse(endDateStr).isBefore(
                                        clock.now().plus(minHoursToEnd, ChronoUnit.HOURS))) {
                                    log.debug("market_skip_eol marketId={} endDate={}", marketId, endDateStr);
                                    skipEol++;
                                    continue;
                                }
                            } catch (DateTimeParseException ignored2) {
                                // Unparseable end-date — let through rather than silently filter.
                                log.debug("market_end_date_unparseable marketId={} endDate={}", marketId, endDateStr);
                            }
                        }

                        // All gates passed — collect with sort keys.
                        candidates.add(new Candidate(
                            item,
                            vol24h  != null ? vol24h : 0d,
                            vol     != null ? vol     : 0d
                        ));

                    } catch (Exception e) {
                        log.warn("market_item_error marketId={} error={}",
                                 item.getOrDefault("id", "unknown"), e.getMessage(), e);
                    }
                }

                if (page.size() < PAGE_LIMIT) break;
                offset += PAGE_LIMIT;
            }

            // ── Phase 2: Sort descending by 24h vol; tiebreak by lifetime vol ─
            candidates.sort((a, b) -> {
                int cmp = Double.compare(b.volume24h(), a.volume24h()); // DESC
                return cmp != 0 ? cmp : Double.compare(b.volumeLifetime(), a.volumeLifetime()); // DESC
            });

            // ── Phase 3: Scale gate — cap at max-markets ──────────────────────
            int     afterFilters    = candidates.size();
            String  cutoffVol24hStr = null;
            List<Candidate> capped;

            if (candidates.size() > maxMarkets) {
                // The "water line": volume24h of the last market that made the cut.
                cutoffVol24hStr = String.format("%.2f", candidates.get(maxMarkets - 1).volume24h());
                capped = candidates.subList(0, maxMarkets);
            } else {
                capped = candidates;
            }

            // ── Phase 4: Upsert the capped set ───────────────────────────────
            int kept = 0;
            for (Candidate c : capped) {
                try {
                    doUpsert(c.raw());
                    kept++;
                } catch (Exception e) {
                    log.warn("market_item_error marketId={} error={}",
                             c.raw().getOrDefault("id", "unknown"), e.getMessage(), e);
                }
            }

            trackedCount.set(kept);
            log.info("market_poll_complete of={} kept_after_filters={} kept_after_cap={} "
                     + "cutoff_volume24hr={} skip_lifetime={} skip_24h={} skip_eol={}",
                     total, afterFilters, kept, cutoffVol24hStr, skipLifetime, skip24h, skipEol);

        } catch (Exception e) {
            log.error("market_poll_failed error={}", e.getMessage(), e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private List<Map<String, Object>> fetchPage(int offset) {
        Supplier<List<Map<String, Object>>> call = () -> {
            String body = gammaClient.get()
                .uri(u -> u.path("/markets")
                           .queryParam("active",  "true")
                           .queryParam("closed",  "false")
                           .queryParam("limit",   PAGE_LIMIT)
                           .queryParam("offset",  offset)
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
     * Writes one quality-and-scale-passed market to DynamoDB.
     * Preserves the {@code isWatched} flag via a read-before-write get.
     * Called only for markets that survived both filter phases.
     */
    private void doUpsert(Map<String, Object> item) {
        String marketId = stringOrNull(item, "id");

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
        Market existing  = marketsTable.getItem(key);
        Boolean isWatched = (existing != null && existing.getIsWatched() != null)
                            ? existing.getIsWatched() : Boolean.FALSE;

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
        market.setClobTokenIds(stringOrNull(item, "clobTokenIds"));
        market.setConditionId(stringOrNull(item, "conditionId"));

        marketsTable.putItem(market);
        log.debug("market_upserted marketId={} category={}", marketId, category);
    }

    /**
     * Parses a numeric field from the API item map.
     * Returns {@code null} if the field is absent or cannot be parsed, so callers can
     * distinguish "no data" (let through) from "value below threshold" (filter out).
     */
    private static Double parseDouble(Map<String, Object> item, String key) {
        Object v = item.get(key);
        if (v == null) return null;
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
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
