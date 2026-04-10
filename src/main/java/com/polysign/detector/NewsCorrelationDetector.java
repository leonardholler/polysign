package com.polysign.detector;

import com.polysign.alert.AlertIdFactory;
import com.polysign.alert.AlertService;
import com.polysign.common.AppClock;
import com.polysign.model.Alert;
import com.polysign.model.Article;
import com.polysign.model.Market;
import com.polysign.model.MarketNewsMatch;
import com.polysign.processing.NewsMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Correlates newly ingested news articles against all active prediction markets.
 *
 * <p>Called by {@link com.polysign.processing.NewsConsumer} for each article that
 * arrives on the {@code news-to-process} SQS queue.
 *
 * <p><b>Scoring:</b> {@link NewsMatcher} uses asymmetric containment /
 * market-side coverage — {@code matches / marketKw.size()} — not Jaccard.
 * An alert fires when:
 * <ul>
 *   <li>score &ge; {@code polysign.detectors.news.min-score} (default 0.5)</li>
 *   <li>market 24-hour volume &ge; {@code polysign.detectors.news.min-volume-usdc}
 *       (default $100 k)</li>
 * </ul>
 *
 * <p><b>Deduplication:</b> {@link AlertIdFactory} with {@link Duration#ZERO} —
 * one article &times; one market = one alert forever. This mirrors the
 * {@link WalletActivityDetector} pattern where {@code txHash} is the canonical
 * payload.
 *
 * <p><b>Market cache:</b> active markets are loaded once per 5-minute window via
 * a volatile TTL cache ({@link #getOrLoadMarkets()}). {@link #clearCache()} is
 * package-private for integration-test use.
 */
@Component
public class NewsCorrelationDetector {

    private static final Logger log = LoggerFactory.getLogger(NewsCorrelationDetector.class);
    static final String ALERT_TYPE = "news_correlation";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final DynamoDbTable<Market>          marketsTable;
    private final DynamoDbTable<MarketNewsMatch> matchesTable;
    private final AlertService                   alertService;
    private final NewsMatcher                    newsMatcher;
    private final AppClock                       clock;
    private final double                         minScore;
    private final double                         minVolumeUsdc;

    // TTL cache — refreshed every CACHE_TTL, shared across all checkMarkets() calls.
    private volatile List<Market> cachedMarkets;
    private volatile Instant      cacheExpiresAt;

    public NewsCorrelationDetector(
            DynamoDbTable<Market>          marketsTable,
            DynamoDbTable<MarketNewsMatch> matchesTable,
            AlertService                   alertService,
            NewsMatcher                    newsMatcher,
            AppClock                       clock,
            @Value("${polysign.detectors.news.min-score:0.5}")        double minScore,
            @Value("${polysign.detectors.news.min-volume-usdc:100000}") double minVolumeUsdc) {
        this.marketsTable  = marketsTable;
        this.matchesTable  = matchesTable;
        this.alertService  = alertService;
        this.newsMatcher   = newsMatcher;
        this.clock         = clock;
        this.minScore      = minScore;
        this.minVolumeUsdc = minVolumeUsdc;
    }

    /**
     * Checks one article against all cached active markets.
     * Called by NewsConsumer for each dequeued {@code articleId}.
     *
     * @param article fully populated article with keywords
     */
    public void checkMarkets(Article article) {
        List<Market> markets = getOrLoadMarkets();
        for (Market market : markets) {
            try {
                checkMarket(article, market);
            } catch (Exception e) {
                log.warn("event=news_check_failed marketId={} articleId={} error={}",
                        market.getMarketId(), article.getArticleId(), e.getMessage());
            }
        }
    }

    private void checkMarket(Article article, Market market) {
        Set<String> articleKw = article.getKeywords();
        Set<String> marketKw  = market.getKeywords();

        double score = newsMatcher.score(articleKw, marketKw);
        if (score < minScore) return;

        double vol = parseVolume(market);
        if (vol < minVolumeUsdc) {
            log.debug("event=news_volume_skip marketId={} volume24h={}", market.getMarketId(), vol);
            return;
        }

        // Matched keywords: intersection, used for attribution analysis (Phase 7.5).
        Set<String> matched = new HashSet<>(articleKw != null ? articleKw : Set.of());
        if (marketKw != null) matched.retainAll(marketKw);

        // Write market_news_matches row BEFORE alert (safe to duplicate — same PK/SK overwrites).
        MarketNewsMatch newsMatch = new MarketNewsMatch();
        newsMatch.setMarketId(market.getMarketId());
        newsMatch.setArticleId(article.getArticleId());
        newsMatch.setScore(score);
        newsMatch.setMatchedKeywords(matched.isEmpty() ? null : matched);
        newsMatch.setCreatedAt(clock.nowIso());
        newsMatch.setArticleTitle(article.getTitle());
        newsMatch.setArticleUrl(article.getUrl());
        matchesTable.putItem(newsMatch);

        // alertId: Duration.ZERO disables bucketing — one article x one market = one alert.
        Instant now       = clock.now();
        String alertId    = AlertIdFactory.generate(
                ALERT_TYPE, market.getMarketId(), now, Duration.ZERO, article.getArticleId());
        Instant createdAt = AlertIdFactory.bucketedInstant(now, Duration.ZERO);

        Alert alert = new Alert();
        alert.setAlertId(alertId);
        alert.setCreatedAt(createdAt.toString());
        alert.setType(ALERT_TYPE);
        alert.setSeverity("warning");
        alert.setMarketId(market.getMarketId());
        alert.setTitle("News match: " + truncate(article.getTitle(), 80));
        alert.setDescription("Score " + String.format("%.2f", score)
                + " — " + truncate(article.getTitle(), 120));
        alert.setMetadata(Map.of(
                "articleId",      article.getArticleId(),
                "articleTitle",   article.getTitle()   != null ? article.getTitle()   : "",
                "articleUrl",     article.getUrl()     != null ? article.getUrl()     : "",
                "score",          String.valueOf(score),
                "marketQuestion", market.getQuestion() != null ? market.getQuestion() : "",
                "detectedAt",     now.toString()
        ));

        boolean created = alertService.tryCreate(alert);
        if (created) {
            log.info("event=news_alert_fired alertId={} marketId={} articleId={} score={}",
                    alertId, market.getMarketId(), article.getArticleId(), score);
        }
    }

    // ── TTL market cache ───────────────────────────────────────────────────────

    private synchronized List<Market> getOrLoadMarkets() {
        Instant now = clock.now();
        if (cachedMarkets == null || now.isAfter(cacheExpiresAt)) {
            List<Market> fresh = new ArrayList<>();
            marketsTable.scan().items().forEach(fresh::add);
            cachedMarkets  = fresh;
            cacheExpiresAt = now.plus(CACHE_TTL);
            log.debug("event=news_cache_refreshed markets={}", fresh.size());
        }
        return cachedMarkets;
    }

    /**
     * Clears the market cache. Public for integration test access across packages —
     * forces a full reload after seeding test data into DynamoDB.
     */
    public synchronized void clearCache() {
        cachedMarkets  = null;
        cacheExpiresAt = null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private double parseVolume(Market market) {
        String v = market.getVolume24h();
        if (v == null || v.isBlank()) {
            log.warn("event=news_volume_null marketId={}", market.getMarketId());
            return 0.0;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            log.warn("event=news_volume_parse_error marketId={} volume24h={} error={}",
                    market.getMarketId(), v, e.getMessage());
            return 0.0;
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
