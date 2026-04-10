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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Correlates newly ingested news articles against all active prediction markets.
 *
 * <p>Called by {@link com.polysign.processing.NewsConsumer} for each article that
 * arrives on the {@code news-to-process} SQS queue.
 *
 * <p><b>Scoring pipeline (two-stage):</b>
 * <ol>
 *   <li><b>Keyword pre-filter</b> (cheap, no API cost): {@link NewsMatcher} asymmetric
 *       containment score must exceed {@code keyword-prefilter-threshold} (default 0.3).</li>
 *   <li><b>LLM sentiment analysis</b> (only for pre-filter survivors): calls the
 *       Anthropic API via {@link ClaudeSentimentService} to score directional sentiment
 *       (−1.0 = strongly NO, +1.0 = strongly YES). An alert fires when
 *       {@code |sentiment| ≥ sentiment-relevance-threshold} and
 *       {@code confidence ≥ sentiment-confidence-threshold}.</li>
 * </ol>
 *
 * <p><b>Fallback:</b> when {@link ClaudeSentimentService#analyze} returns null (API
 * unavailable, key not configured, circuit open), the detector falls back to
 * keyword-only scoring: alerts fire when keyword score ≥ {@code min-score} (0.75).
 * The alert metadata includes {@code scoringMethod="keyword_fallback"}.
 *
 * <p><b>Deduplication:</b> {@link AlertIdFactory} with a 24-hour bucketing window
 * and the {@code articleId} as payload hash — one article × one market = one alert per day.
 *
 * <p><b>Market cache:</b> active markets are loaded once per 5-minute TTL cache
 * ({@link #getOrLoadMarkets()}). {@link #clearCache()} is package-private for integration
 * test use.
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
    private final ClaudeSentimentService         sentimentService;
    private final AppClock                       clock;
    private final double                         minScore;
    private final double                         minVolumeUsdc;
    private final Duration                       dedupeWindow;
    private final int                            maxMatchesPerArticle;
    private final double                         keywordPrefilterThreshold;
    private final double                         sentimentRelevanceThreshold;
    private final double                         sentimentConfidenceThreshold;

    // TTL cache — refreshed every CACHE_TTL, shared across all checkMarkets() calls.
    private volatile List<Market> cachedMarkets;
    private volatile Instant      cacheExpiresAt;

    // ── Sentiment API rate-limiting and per-app-lifetime pair caching ────────────
    private static final int MAX_SENTIMENT_PER_MINUTE = 5;
    private final Set<String>   sentimentCallCache      = ConcurrentHashMap.newKeySet();
    private final AtomicInteger sentimentCallsThisMinute = new AtomicInteger(0);
    private final AtomicLong    sentimentMinuteWindowStart = new AtomicLong(System.currentTimeMillis());

    public NewsCorrelationDetector(
            DynamoDbTable<Market>          marketsTable,
            DynamoDbTable<MarketNewsMatch> matchesTable,
            AlertService                   alertService,
            NewsMatcher                    newsMatcher,
            ClaudeSentimentService         sentimentService,
            AppClock                       clock,
            @Value("${polysign.detectors.news.min-score:0.75}")                      double minScore,
            @Value("${polysign.detectors.news.min-volume-usdc:100000}")              double minVolumeUsdc,
            @Value("${polysign.detectors.news.dedupe-window-minutes:1440}")          int    dedupeWindowMinutes,
            @Value("${polysign.detectors.news.max-matches-per-article:3}")           int    maxMatchesPerArticle,
            @Value("${polysign.detectors.news.keyword-prefilter-threshold:0.3}")     double keywordPrefilterThreshold,
            @Value("${polysign.detectors.news.sentiment-relevance-threshold:0.3}")   double sentimentRelevanceThreshold,
            @Value("${polysign.detectors.news.sentiment-confidence-threshold:0.5}")  double sentimentConfidenceThreshold) {
        this.marketsTable                = marketsTable;
        this.matchesTable                = matchesTable;
        this.alertService                = alertService;
        this.newsMatcher                 = newsMatcher;
        this.sentimentService            = sentimentService;
        this.clock                       = clock;
        this.minScore                    = minScore;
        this.minVolumeUsdc               = minVolumeUsdc;
        this.dedupeWindow                = Duration.ofMinutes(dedupeWindowMinutes);
        this.maxMatchesPerArticle        = maxMatchesPerArticle;
        this.keywordPrefilterThreshold   = keywordPrefilterThreshold;
        this.sentimentRelevanceThreshold = sentimentRelevanceThreshold;
        this.sentimentConfidenceThreshold = sentimentConfidenceThreshold;
    }

    /** Scored candidate market for an article. */
    record Candidate(
            Market market,
            double score,           // LLM |sentiment| or keyword score
            Set<String> matched,
            Double sentimentRaw,    // raw sentiment float (-1.0 to +1.0), null if keyword fallback
            Double sentimentConfidence,
            String sentimentDirection,  // "YES" or "NO", null if keyword fallback
            String sentimentReasoning,
            String scoringMethod    // "claude" or "keyword_fallback"
    ) {}

    /**
     * Checks one article against all cached active markets.
     * Fires alerts for the top {@code maxMatchesPerArticle} markets by score.
     * Called by NewsConsumer for each dequeued {@code articleId}.
     *
     * @param article fully populated article with keywords
     */
    public void checkMarkets(Article article) {
        List<Market> markets = getOrLoadMarkets();
        List<Candidate> candidates = new ArrayList<>();

        for (Market market : markets) {
            try {
                Candidate c = scoreMarket(article, market);
                if (c != null) candidates.add(c);
            } catch (Exception e) {
                log.warn("event=news_check_failed marketId={} articleId={} error={}",
                        market.getMarketId(), article.getArticleId(), e.getMessage());
            }
        }

        // Cap: take top N by score to prevent one article from flooding the feed.
        candidates.stream()
                .sorted(Comparator.comparingDouble(Candidate::score).reversed())
                .limit(maxMatchesPerArticle)
                .forEach(c -> fireAlert(article, c));
    }

    /** Returns a Candidate if the article-market pair passes all filters, or null. */
    Candidate scoreMarket(Article article, Market market) {
        Set<String> articleKw = article.getKeywords();
        Set<String> marketKw  = market.getKeywords();

        // Stage 1: cheap keyword pre-filter (always runs — no API cost)
        double keywordScore = newsMatcher.score(articleKw, marketKw);
        if (keywordScore < keywordPrefilterThreshold) return null;

        // Require ≥2 matched keywords to prevent single-token false positives.
        Set<String> matched = new HashSet<>(articleKw != null ? articleKw : Set.of());
        if (marketKw != null) matched.retainAll(marketKw);
        if (matched.size() < 2) {
            log.debug("event=news_keyword_count_skip marketId={} matched={}", market.getMarketId(), matched.size());
            return null;
        }

        double vol = parseVolume(market);
        if (vol < minVolumeUsdc) {
            log.debug("event=news_volume_skip marketId={} volume24h={}", market.getMarketId(), vol);
            return null;
        }

        // Stage 2: LLM sentiment analysis (only for pre-filter survivors)
        double currentPrice = market.getCurrentYesPrice() != null
                ? market.getCurrentYesPrice().doubleValue() : 0.5;

        // Cache check: each article-market pair is analyzed at most once per app lifetime.
        String cacheKey = article.getArticleId() + ":" + market.getMarketId();
        if (!sentimentCallCache.contains(cacheKey)) {
            // Rate limit: max MAX_SENTIMENT_PER_MINUTE calls per rolling minute.
            long nowMs        = System.currentTimeMillis();
            long windowStart  = sentimentMinuteWindowStart.get();
            if (nowMs - windowStart >= 60_000L
                    && sentimentMinuteWindowStart.compareAndSet(windowStart, nowMs)) {
                sentimentCallsThisMinute.set(0);
            }
            if (sentimentCallsThisMinute.get() >= MAX_SENTIMENT_PER_MINUTE) {
                log.info("sentiment_analysis_call articleId={} marketId={} status=rate_limited",
                        article.getArticleId(), market.getMarketId());
            } else {
                sentimentCallsThisMinute.incrementAndGet();
                if (sentimentCallCache.size() < 50_000) sentimentCallCache.add(cacheKey);
                ClaudeSentimentService.SentimentResult sentiment =
                        sentimentService.analyze(market.getQuestion(), currentPrice,
                                                 article.getTitle(), article.getSummary());
                if (sentiment != null) {
                    log.info("sentiment_analysis_call articleId={} marketId={} status=success",
                            article.getArticleId(), market.getMarketId());
                    if (!sentiment.relevant()) {
                        log.debug("event=news_sentiment_not_relevant marketId={} articleId={}",
                                market.getMarketId(), article.getArticleId());
                        return null;
                    }
                    if (Math.abs(sentiment.sentiment()) < sentimentRelevanceThreshold
                            || sentiment.confidence() < sentimentConfidenceThreshold) {
                        log.debug("event=news_sentiment_low_signal marketId={} sentiment={} confidence={}",
                                market.getMarketId(), sentiment.sentiment(), sentiment.confidence());
                        return null;
                    }
                    String direction = sentiment.sentiment() > 0 ? "YES" : "NO";
                    return new Candidate(market, Math.abs(sentiment.sentiment()), matched,
                            sentiment.sentiment(), sentiment.confidence(),
                            direction, sentiment.reasoning(), "claude");
                }
                log.info("sentiment_analysis_call articleId={} marketId={} status=error",
                        article.getArticleId(), market.getMarketId());
            }
        } else {
            log.info("sentiment_analysis_call articleId={} marketId={} status=cached",
                    article.getArticleId(), market.getMarketId());
        }

        // Fallback: sentiment API unavailable/cached/rate-limited — use keyword-score gate
        log.debug("event=news_keyword_fallback marketId={} keywordScore={}", market.getMarketId(), keywordScore);
        if (keywordScore < minScore) return null;
        return new Candidate(market, keywordScore, matched,
                null, null, null, null, "keyword_fallback");
    }

    private void fireAlert(Article article, Candidate c) {
        Market market = c.market();
        double score  = c.score();
        Set<String> matched = c.matched();

        // Write market_news_matches row — score reflects LLM |sentiment| or keyword score.
        MarketNewsMatch newsMatch = new MarketNewsMatch();
        newsMatch.setMarketId(market.getMarketId());
        newsMatch.setArticleId(article.getArticleId());
        newsMatch.setScore(score);
        newsMatch.setMatchedKeywords(matched.isEmpty() ? null : matched);
        newsMatch.setCreatedAt(clock.nowIso());
        newsMatch.setArticleTitle(article.getTitle());
        newsMatch.setArticleUrl(article.getUrl());
        matchesTable.putItem(newsMatch);

        Instant now       = clock.now();
        String alertId    = AlertIdFactory.generate(
                ALERT_TYPE, market.getMarketId(), now, dedupeWindow, article.getArticleId());
        Instant createdAt = AlertIdFactory.bucketedInstant(now, dedupeWindow);

        Map<String, String> metadata = new HashMap<>();
        metadata.put("articleId",      article.getArticleId());
        metadata.put("articleTitle",   article.getTitle()   != null ? article.getTitle()   : "");
        metadata.put("articleUrl",     article.getUrl()     != null ? article.getUrl()     : "");
        metadata.put("score",          String.valueOf(score));
        metadata.put("marketQuestion", market.getQuestion() != null ? market.getQuestion() : "");
        metadata.put("scoringMethod",  c.scoringMethod());
        metadata.put("keywordScore",   String.format("%.3f", newsMatcher.score(
                article.getKeywords(), market.getKeywords())));
        metadata.put("detectedAt",     now.toString());

        if ("claude".equals(c.scoringMethod())) {
            metadata.put("sentimentScore",     String.format("%.3f", c.sentimentRaw()));
            metadata.put("sentimentDirection", c.sentimentDirection());
            metadata.put("sentimentConfidence", String.format("%.3f", c.sentimentConfidence()));
            if (c.sentimentReasoning() != null && !c.sentimentReasoning().isEmpty()) {
                metadata.put("sentimentReasoning", c.sentimentReasoning());
            }
        }

        Alert alert = new Alert();
        alert.setAlertId(alertId);
        alert.setCreatedAt(createdAt.toString());
        alert.setType(ALERT_TYPE);
        alert.setSeverity("warning");
        alert.setMarketId(market.getMarketId());
        alert.setTitle("News match: " + truncate(article.getTitle(), 80));
        alert.setDescription(buildDescription(score, c));
        String link;
        if (market.getEventSlug() != null) {
            link = "https://polymarket.com/event/" + market.getEventSlug();
        } else {
            String q = market.getQuestion() != null ? market.getQuestion() : market.getMarketId();
            link = "https://polymarket.com/search?query="
                    + URLEncoder.encode(q, StandardCharsets.UTF_8);
        }
        alert.setLink(link);
        alert.setMetadata(metadata);

        boolean created = alertService.tryCreate(alert);
        if (created) {
            log.info("event=news_alert_fired alertId={} marketId={} articleId={} score={} method={}",
                    alertId, market.getMarketId(), article.getArticleId(), score, c.scoringMethod());
        }
    }

    private String buildDescription(double score, Candidate c) {
        if ("claude".equals(c.scoringMethod())) {
            String dir = "YES".equals(c.sentimentDirection()) ? "bullish" : "bearish";
            return String.format("%s %.0f%% — %s",
                    dir, Math.abs(c.sentimentRaw()) * 100.0,
                    c.sentimentReasoning() != null ? c.sentimentReasoning() : "");
        }
        return String.format("Score %.2f (keyword)", score);
    }

    // ── TTL market cache ───────────────────────────────────────────────────────

    synchronized List<Market> getOrLoadMarkets() {
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
