package com.polysign.poller;

import com.polysign.common.AppClock;
import com.polysign.common.CorrelationId;
import com.polysign.model.Article;
import com.polysign.processing.KeywordExtractor;
import com.polysign.processing.UrlCanonicalizer;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Polls configured RSS feeds every 5 minutes and ingests new articles.
 *
 * <p>For each feed entry:
 * <ol>
 *   <li>Canonicalize the article URL via {@link UrlCanonicalizer} → derive a stable
 *       {@code articleId} (SHA-256 of canonical URL)</li>
 *   <li>Fetch the raw article HTML and archive it to S3 at
 *       {@code articles/{yyyy}/{MM}/{dd}/{articleId}.html}</li>
 *   <li>Extract keywords from title + description via {@link KeywordExtractor}</li>
 *   <li>Write the {@link Article} metadata to the DynamoDB {@code articles} table
 *       (idempotent: same {@code articleId} PK overwrites identical data)</li>
 *   <li>Enqueue the {@code articleId} to the {@code news-to-process} SQS queue for
 *       downstream correlation detection</li>
 * </ol>
 *
 * <p><b>Defensive polling discipline (Decision B):</b>
 * <ul>
 *   <li>Feed-level try/catch: logs {@code event=rss_feed_failed} at WARN and continues
 *       to the next feed. One bad feed never kills the poll cycle.</li>
 *   <li>Item-level try/catch: logs {@code event=rss_item_failed} at WARN and continues
 *       to the next item. One bad item never kills a feed's processing.</li>
 * </ul>
 *
 * <p>All outbound HTTP calls (feed fetch, article HTML fetch) use {@code java.net.http.HttpClient}
 * wrapped in Resilience4j CB + retry ({@code rss-news} instance). WebClient is intentionally
 * NOT used here: Rome's {@link com.rometools.rome.io.SyndFeedInput} requires a blocking
 * {@link java.io.InputStream} and does not compose with reactive WebClient. This is the one
 * documented exception to the WebClient convention in CONVENTIONS.md.
 */
@Component
public class RssPoller {

    private static final Logger log = LoggerFactory.getLogger(RssPoller.class);
    private static final String R4J_NAME = "rss-news";

    private final List<String>           feedUrls;
    private final DynamoDbTable<Article> articlesTable;
    private final S3Client               s3Client;
    private final SqsClient             sqsClient;
    private final KeywordExtractor       keywordExtractor;
    private final AppClock               clock;
    private final String                 archivesBucket;
    private final String                 newsQueueName;
    private final CircuitBreaker         circuitBreaker;
    private final Retry                  retry;

    // Lazily resolved — BootstrapRunner creates the queue after beans are wired.
    private volatile String newsQueueUrl;

    public RssPoller(
            @Value("${polysign.pollers.rss.feeds}") List<String> feedUrls,
            DynamoDbTable<Article> articlesTable,
            S3Client s3Client,
            SqsClient sqsClient,
            KeywordExtractor keywordExtractor,
            AppClock clock,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry,
            @Value("${polysign.s3.archives-bucket:polysign-archives}") String archivesBucket,
            @Value("${polysign.sqs.queues.news-to-process:news-to-process}") String newsQueueName) {

        this.feedUrls         = feedUrls;
        this.articlesTable    = articlesTable;
        this.s3Client         = s3Client;
        this.sqsClient        = sqsClient;
        this.keywordExtractor = keywordExtractor;
        this.clock            = clock;
        this.archivesBucket   = archivesBucket;
        this.newsQueueName    = newsQueueName;
        this.circuitBreaker   = cbRegistry.circuitBreaker(R4J_NAME);
        this.retry            = retryRegistry.retry(R4J_NAME);
    }

    @Scheduled(
        fixedDelayString   = "${polysign.pollers.rss.interval-ms:300000}",
        initialDelayString = "${polysign.pollers.rss.initial-delay-ms:15000}"
    )
    public void pollFeeds() {
        int totalNew = 0;
        for (String feedUrl : feedUrls) {
            try (var ignored = CorrelationId.set("rss-" + feedUrl.hashCode())) {
                int newInFeed = processFeed(feedUrl);
                totalNew += newInFeed;
            } catch (Exception e) {
                // Feed-level catch: one bad feed must not kill the whole cycle.
                log.warn("event=rss_feed_failed feedUrl={} error={}", feedUrl, e.getMessage());
            }
        }
        log.info("event=rss_poll_complete feeds={} newArticles={}", feedUrls.size(), totalNew);
    }

    /**
     * Fetches and processes one RSS feed. Returns the count of new articles written.
     */
    private int processFeed(String feedUrl) throws Exception {
        SyndFeed feed = fetchFeed(feedUrl);
        String source = feed.getTitle() != null ? feed.getTitle() : feedUrl;

        int newCount = 0;
        for (SyndEntry entry : feed.getEntries()) {
            try {
                boolean isNew = processEntry(entry, feedUrl, source);
                if (isNew) newCount++;
            } catch (Exception e) {
                // Item-level catch: one bad item must not kill the feed.
                String guid = entry.getUri() != null ? entry.getUri() : "(no-guid)";
                log.warn("event=rss_item_failed feedUrl={} itemGuid={} error={}",
                        feedUrl, guid, e.getMessage());
            }
        }
        log.info("event=rss_feed_polled feedUrl={} entries={} newArticles={}", feedUrl,
                feed.getEntries().size(), newCount);
        return newCount;
    }

    /**
     * Processes a single RSS entry. Returns {@code true} if this is a new article.
     */
    private boolean processEntry(SyndEntry entry, String feedUrl, String source) throws Exception {
        // 1. Determine canonical URL and derive stable articleId
        String rawUrl = entryUrl(entry);
        if (rawUrl == null || rawUrl.isBlank()) {
            log.warn("event=rss_item_failed feedUrl={} itemGuid={} error=no-url",
                    feedUrl, entry.getUri());
            return false;
        }
        String articleId = UrlCanonicalizer.sha256(rawUrl);

        // 2. Fetch raw article HTML and archive to S3
        String publishedDate = formatDate(entry.getPublishedDate());
        String s3Key         = buildS3Key(publishedDate, articleId);
        fetchAndArchive(rawUrl, s3Key);

        // 3. Extract keywords from title + description
        String title       = entry.getTitle() != null ? entry.getTitle() : "";
        String description = entry.getDescription() != null
                && entry.getDescription().getValue() != null
                ? entry.getDescription().getValue() : "";
        Set<String> keywords = keywordExtractor.extract(title + " " + description);

        // 4. Write Article to DynamoDB (natural idempotent: same PK overwrites same data)
        Article article = new Article();
        article.setArticleId(articleId);
        article.setTitle(title);
        article.setUrl(UrlCanonicalizer.canonicalize(rawUrl));
        article.setSource(source);
        article.setPublishedAt(publishedDate);
        article.setSummary(truncate(description, 500));
        article.setKeywords(keywords);
        article.setS3Key(s3Key);
        articlesTable.putItem(article);

        // 5. Enqueue to news-to-process
        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(resolveQueueUrl())
                .messageBody(articleId)
                .messageDeduplicationId(null) // standard queue — no deduplication ID needed
                .build());

        log.info("event=rss_article_new articleId={} source={} title={}",
                articleId, source, truncate(title, 80));
        return true;
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    /**
     * Fetches an RSS feed XML and parses it with Rome.
     * Wrapped in Resilience4j retry + circuit breaker.
     */
    private SyndFeed fetchFeed(String feedUrl) throws Exception {
        Supplier<SyndFeed> call = () -> {
            try {
                HttpClient httpClient = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(feedUrl))
                        .header("User-Agent", "polysign/0.1 (monitoring-bot; not-a-trading-bot)")
                        .timeout(Duration.ofSeconds(15))
                        .build();
                HttpResponse<InputStream> resp = httpClient.send(req,
                        HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                    throw new RuntimeException("HTTP " + resp.statusCode() + " for feed: " + feedUrl);
                }
                try (InputStream body = resp.body()) {
                    return new SyndFeedInput().build(new XmlReader(body));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        return Retry.decorateSupplier(retry,
               CircuitBreaker.decorateSupplier(circuitBreaker, call)).get();
    }

    /**
     * Fetches article HTML and writes it to S3.
     * Wrapped in Resilience4j retry + circuit breaker.
     * Idempotent — same S3 key overwrites identical content.
     */
    private void fetchAndArchive(String articleUrl, String s3Key) {
        Supplier<Void> call = () -> {
            try {
                HttpClient httpClient = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(articleUrl))
                        .header("User-Agent", "polysign/0.1 (monitoring-bot; not-a-trading-bot)")
                        .timeout(Duration.ofSeconds(15))
                        .build();
                HttpResponse<byte[]> resp = httpClient.send(req,
                        HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    s3Client.putObject(
                            PutObjectRequest.builder()
                                    .bucket(archivesBucket)
                                    .key(s3Key)
                                    .contentType("text/html")
                                    .build(),
                            RequestBody.fromBytes(resp.body()));
                    log.debug("event=article_archived s3Key={}", s3Key);
                } else {
                    log.debug("event=article_archive_skipped s3Key={} status={}", s3Key,
                            resp.statusCode());
                }
                return null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        try {
            Retry.decorateSupplier(retry,
                   CircuitBreaker.decorateSupplier(circuitBreaker, call)).get();
        } catch (Exception e) {
            // Archive failure is non-fatal — the article is still processed for keywords.
            log.warn("event=article_archive_failed url={} error={}", articleUrl, e.getMessage());
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String resolveQueueUrl() {
        String url = newsQueueUrl;
        if (url == null) {
            url = sqsClient.getQueueUrl(r -> r.queueName(newsQueueName)).queueUrl();
            newsQueueUrl = url;
            log.info("RssPoller resolved queue URL: {}", url);
        }
        return url;
    }

    /** Extracts the most useful URL from an RSS entry. */
    private static String entryUrl(SyndEntry entry) {
        if (entry.getLink() != null && !entry.getLink().isBlank()) return entry.getLink();
        if (entry.getUri()  != null && !entry.getUri().isBlank())  return entry.getUri();
        return null;
    }

    /** Formats a {@link Date} as ISO-8601 UTC string; falls back to clock.now() if null. */
    private String formatDate(Date date) {
        if (date == null) return clock.nowIso();
        return date.toInstant()
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    /**
     * Builds the S3 archive key from the published date and articleId.
     * Key format: {@code articles/yyyy/MM/dd/{articleId}.html}
     * Falls back to current date if publishedAt cannot be parsed.
     */
    private String buildS3Key(String publishedAt, String articleId) {
        try {
            var dt = java.time.OffsetDateTime.parse(publishedAt);
            return String.format("articles/%04d/%02d/%02d/%s.html",
                    dt.getYear(), dt.getMonthValue(), dt.getDayOfMonth(), articleId);
        } catch (Exception e) {
            var now = clock.now().atOffset(ZoneOffset.UTC);
            return String.format("articles/%04d/%02d/%02d/%s.html",
                    now.getYear(), now.getMonthValue(), now.getDayOfMonth(), articleId);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
