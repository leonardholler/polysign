package com.polysign.poller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polysign.common.AppClock;
import com.polysign.common.AppStats;
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
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
     * Gamma API returns gameStartTime as "2026-04-13 15:00:00+00" — space-separated with
     * a short zone offset (+00) that isn't valid ISO-8601. Pattern 'x' handles +HH offsets.
     */
    private static final DateTimeFormatter GAME_START_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssx");

    /**
     * Holds a market that has passed all quality gates, along with its parsed
     * volume values for sorting. The raw API map is kept so {@link #doUpsert}
     * can extract all fields without re-fetching.
     */
    private record Candidate(Map<String, Object> raw, double volume24h, double volumeLifetime) {}

    // ── Configuration ─────────────────────────────────────────────────────────
    private final double         minVolumeUsdc;
    private final double         minVolume24hUsdc;
    private final long           minHoursToEnd;
    private final int            maxMarkets;
    private final List<Pattern>  excludedQuestionPatterns;
    private final Set<String>    excludedCategories;

    // ── Dependencies ──────────────────────────────────────────────────────────
    private final WebClient             gammaClient;
    private final DynamoDbTable<Market> marketsTable;
    private final AppClock              clock;
    private final ObjectMapper          mapper;
    private final CircuitBreaker        circuitBreaker;
    private final Retry                 retry;
    private final AppStats               appStats;
    private final AtomicLong            trackedCount = new AtomicLong(0);

    public MarketPoller(
            @Qualifier("gammaApiClient") WebClient gammaClient,
            DynamoDbTable<Market> marketsTable,
            AppClock clock,
            ObjectMapper mapper,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry,
            MeterRegistry meterRegistry,
            AppStats appStats,
            @Value("${polysign.pollers.market.min-volume-usdc:10000}")    double minVolumeUsdc,
            @Value("${polysign.pollers.market.min-volume-24h-usdc:10000}") double minVolume24hUsdc,
            @Value("${polysign.pollers.market.min-hours-to-end:12}")      long   minHoursToEnd,
            @Value("${polysign.pollers.market.max-markets:400}")          int    maxMarkets,
            @Value("${polysign.pollers.market.excluded-question-patterns:}") String excludedPatternsCsv,
            @Value("${polysign.pollers.market.excluded-categories:}")        String excludedCategoriesCsv) {

        this.minVolumeUsdc    = minVolumeUsdc;
        this.minVolume24hUsdc = minVolume24hUsdc;
        this.minHoursToEnd    = minHoursToEnd;
        this.maxMarkets       = maxMarkets;
        this.excludedQuestionPatterns = Arrays.stream(excludedPatternsCsv.split(","))
                .map(String::trim).filter(s -> !s.isBlank())
                .map(Pattern::compile).collect(Collectors.toList());
        this.excludedCategories = Arrays.stream(excludedCategoriesCsv.split(","))
                .map(String::trim).filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(HashSet::new));
        this.gammaClient      = gammaClient;
        this.marketsTable     = marketsTable;
        this.clock            = clock;
        this.mapper           = mapper;
        this.appStats         = appStats;
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
            int skipPattern  = 0;
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

                        // ── Quality gate 4: excluded question patterns / categories ──
                        String question = stringOrNull(item, "question");
                        if (question != null && !excludedQuestionPatterns.isEmpty()) {
                            final String q = question;
                            if (excludedQuestionPatterns.stream().anyMatch(p -> p.matcher(q).matches())) {
                                log.debug("market_skip_excluded_pattern marketId={} question={}", marketId, q);
                                skipPattern++;
                                continue;
                            }
                        }
                        if (!excludedCategories.isEmpty()) {
                            String slug = stringOrNull(item, "slug");
                            String cat  = CategoryClassifier.classify(question, slug);
                            if (excludedCategories.contains(cat)) {
                                log.debug("market_skip_excluded_category marketId={} category={}", marketId, cat);
                                skipPattern++;
                                continue;
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

            // ── Phase 4: Auto-watch top 25 by volume ─────────────────────────
            Set<String> autoWatch = capped.stream()
                    .limit(25)
                    .map(c -> stringOrNull(c.raw(), "id"))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            // ── Phase 5: Upsert the capped set ───────────────────────────────
            int kept = 0;
            for (Candidate c : capped) {
                try {
                    doUpsert(c.raw(), autoWatch.contains(stringOrNull(c.raw(), "id")));
                    kept++;
                } catch (Exception e) {
                    log.warn("market_item_error marketId={} error={}",
                             c.raw().getOrDefault("id", "unknown"), e.getMessage(), e);
                }
            }

            // ── Phase 6: Unwatch markets that fell out of top 25 ─────────────
            // Scan all markets; any that are still isWatched=true but not in the
            // current autoWatch set are stale auto-watches — reset them to false.
            int unwatched = 0;
            for (Market existing : marketsTable.scan().items()) {
                if (Boolean.TRUE.equals(existing.getIsWatched())
                        && !autoWatch.contains(existing.getMarketId())) {
                    existing.setIsWatched(false);
                    marketsTable.putItem(existing);
                    unwatched++;
                }
            }
            log.info("auto_watch updated={} markets by volume24h", autoWatch.size());
            if (unwatched > 0) {
                log.info("auto_watch_unwatch markets_unwatched={}", unwatched);
            }

            trackedCount.set(kept);
            appStats.setLastMarketPollAt(clock.now());
            log.info("market_poll_complete of={} kept_after_filters={} kept_after_cap={} "
                     + "cutoff_volume24hr={} skip_lifetime={} skip_24h={} skip_eol={} skip_pattern={}",
                     total, afterFilters, kept, cutoffVol24hStr, skipLifetime, skip24h, skipEol, skipPattern);

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
    // Package-private so MarketPollerKeywordTest can call it directly without a live HTTP server.
    void doUpsert(Map<String, Object> item, boolean shouldWatch) {
        String marketId = stringOrNull(item, "id");

        List<String> outcomeList   = parseJsonStringList(item, "outcomes");
        List<String> clobTokenList = parseJsonStringList(item, "clobTokenIds");
        String yesTokenId = clobTokenList.isEmpty() ? null : clobTokenList.get(0);

        String question     = stringOrNull(item, "question");
        String marketSlug   = stringOrNull(item, "slug");
        String category     = CategoryClassifier.classify(question, marketSlug);

        // Extract event-level slug from events[0].slug.
        // The market-level "slug" includes an outcome-ID suffix (e.g. "my-market-554");
        // events[0].slug is the clean event URL slug (e.g. "my-market").
        String eventSlug = null;
        Object eventsRaw = item.get("events");
        if (eventsRaw instanceof List<?> eventsList && !eventsList.isEmpty()) {
            Object first = eventsList.get(0);
            if (first instanceof Map<?, ?> eventMap) {
                Object s = eventMap.get("slug");
                if (s != null && !s.toString().isBlank()) {
                    eventSlug = s.toString();
                }
            }
        }
        if (CategoryClassifier.OTHER.equals(category)) {
            log.info("market_category_other marketId={} question={}", marketId, question);
        }

        Key key = Key.builder().partitionValue(marketId).build();
        Market existing  = marketsTable.getItem(key);
        // Fix 6: skip essentially-resolved markets (price near 0 or 1 means effectively decided).
        // Gamma active=true&closed=false can still include briefly-active resolved markets.
        if (existing != null && existing.getCurrentYesPrice() != null) {
            double p = existing.getCurrentYesPrice().doubleValue();
            if (p >= 0.98 || p <= 0.02) {
                log.debug("market_skip_resolved marketId={} currentYesPrice={}", marketId, p);
                return;
            }
        }
        Boolean isWatched = shouldWatch ? Boolean.TRUE
                            : (existing != null && existing.getIsWatched() != null)
                            ? existing.getIsWatched() : Boolean.FALSE;

        Market market = new Market();
        market.setMarketId(marketId);
        market.setQuestion(question);
        market.setCategory(category);
        market.setEndDate(stringOrNull(item, "endDate"));
        market.setVolume(stringOrNull(item, "volume"));
        market.setVolume24h(stringOrNull(item, "volume24hr")); // Gamma field name is volume24hr
        market.setOutcomes(outcomeList);
        market.setIsWatched(isWatched);
        market.setUpdatedAt(clock.nowIso());
        market.setYesTokenId(yesTokenId);
        market.setClobTokenIds(stringOrNull(item, "clobTokenIds"));
        market.setSlug(marketSlug);
        market.setEventSlug(eventSlug);
        market.setConditionId(stringOrNull(item, "conditionId"));
        // "closed" is present in Gamma API responses but always false here because we filter
        // closed=false. Set it from the response anyway so re-polling a market that closes
        // between cycles will eventually flip the flag. resolvedOutcomePrice is not in the
        // Gamma /markets endpoint — ResolutionSweeper must use a separate closed-markets poll.
        Object closedVal = item.get("closed");
        market.setClosed(closedVal != null ? Boolean.parseBoolean(closedVal.toString()) : null);

        // ── Resolution-detection fields ───────────────────────────────────────
        Object activeVal = item.get("active");
        market.setActive(activeVal != null ? Boolean.parseBoolean(activeVal.toString()) : null);

        Object acceptingOrdersVal = item.get("acceptingOrders");
        market.setAcceptingOrders(acceptingOrdersVal != null ? Boolean.parseBoolean(acceptingOrdersVal.toString()) : null);

        // outcomePrices arrives as a JSON-string array: '["0.9995","0.0005"]'
        market.setOutcomePrices(parseJsonStringList(item, "outcomePrices"));

        // gameStartTime is in non-ISO format; parseGameStartTime returns null on failure
        market.setGameStartTime(parseGameStartTime(stringOrNull(item, "gameStartTime")));

        // resolvedBy is an Ethereum address string, may be empty "" or absent
        market.setResolvedBy(stringOrNull(item, "resolvedBy"));

        // umaResolutionStatuses arrives as a JSON-string array: '[]'
        market.setUmaResolutionStatuses(parseJsonStringList(item, "umaResolutionStatuses"));

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

    /**
     * Parses Gamma's non-standard {@code gameStartTime} format {@code "2026-04-13 15:00:00+00"}
     * into an {@link Instant}. Returns {@code null} if the value is absent, blank, or unparseable.
     */
    private static Instant parseGameStartTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s, GAME_START_FMT).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

}
