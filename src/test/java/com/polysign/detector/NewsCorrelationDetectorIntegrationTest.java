package com.polysign.detector;

import com.polysign.common.AppClock;
import com.polysign.model.Alert;
import com.polysign.model.Article;
import com.polysign.model.Market;
import com.polysign.model.MarketNewsMatch;
import com.polysign.poller.MarketPoller;
import com.polysign.poller.PricePoller;
import com.polysign.poller.RssPoller;
import com.polysign.poller.WalletPoller;
import com.polysign.processing.NewsConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for NewsCorrelationDetector against a live LocalStack DynamoDB.
 *
 * <p>Prerequisites:
 * <ol>
 *   <li>LocalStack running on localhost:4566
 *       ({@code docker compose up localstack -d})</li>
 *   <li>Run with {@code -Dintegration-tests=true}</li>
 * </ol>
 *
 * <p>Skipped by default to keep {@code mvn test} runnable on a fresh clone
 * with no infrastructure.
 */
@EnabledIfSystemProperty(named = "integration-tests", matches = "true")
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = "aws.endpoint-override=http://localhost:4566")
@MockBean({MarketPoller.class, PricePoller.class, WalletPoller.class,
           PriceMovementDetector.class, StatisticalAnomalyDetector.class,
           com.polysign.notification.NotificationConsumer.class,
           RssPoller.class, NewsConsumer.class})
class NewsCorrelationDetectorIntegrationTest {

    /**
     * Unique per test run — prevents cross-run bleed and enables safe parallel
     * execution against a shared LocalStack.
     */
    static final String TEST_MARKET_ID  = "test-market-news-" + UUID.randomUUID();
    static final String TEST_ARTICLE_ID = "test-article-news-" + UUID.randomUUID();

    @Autowired NewsCorrelationDetector      newsCorrelationDetector;
    @Autowired DynamoDbTable<Market>        marketsTable;
    @Autowired DynamoDbTable<Article>       articlesTable;
    @Autowired DynamoDbTable<Alert>         alertsTable;
    @Autowired DynamoDbTable<MarketNewsMatch> matchesTable;
    @Autowired AppClock                     clock;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeEach
    @AfterEach
    void cleanup() {
        // Remove market_news_matches for TEST_MARKET_ID via base-table PK scan
        for (Page<MarketNewsMatch> page : matchesTable.query(
                QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(TEST_MARKET_ID).build()))) {
            for (MarketNewsMatch m : page.items()) {
                matchesTable.deleteItem(Key.builder()
                        .partitionValue(m.getMarketId())
                        .sortValue(m.getArticleId())
                        .build());
            }
        }

        // Remove alerts for TEST_MARKET_ID via GSI
        for (Page<Alert> page : alertsTable
                .index("marketId-createdAt-index")
                .query(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(TEST_MARKET_ID).build()))) {
            for (Alert a : page.items()) {
                alertsTable.deleteItem(Key.builder()
                        .partitionValue(a.getAlertId())
                        .sortValue(a.getCreatedAt())
                        .build());
            }
        }

        // Remove test article
        articlesTable.deleteItem(Key.builder().partitionValue(TEST_ARTICLE_ID).build());

        // Remove test market
        marketsTable.deleteItem(Key.builder().partitionValue(TEST_MARKET_ID).build());

        // Reset cache so each test starts with a fresh market scan
        newsCorrelationDetector.clearCache();
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Test A — high-volume market + matching article must produce exactly one
     * warning alert of type news_correlation with correct metadata.
     */
    @Test
    void newsCorrelationFiresOnHighScoreHighVolumeMatch() {
        Market market = new Market();
        market.setMarketId(TEST_MARKET_ID);
        market.setQuestion("Will the test election result happen?");
        market.setVolume24h("200000");
        market.setKeywords(Set.of("test", "election", "result"));
        marketsTable.putItem(market);

        Article article = new Article();
        article.setArticleId(TEST_ARTICLE_ID);
        article.setTitle("Test election result announced");
        article.setUrl("https://example.com/test-election");
        article.setKeywords(Set.of("test", "election", "result", "winner", "poll"));
        articlesTable.putItem(article);

        newsCorrelationDetector.clearCache();
        newsCorrelationDetector.checkMarkets(article);

        List<Alert> alerts = fetchAlertsForTestMarket();
        assertThat(alerts).hasSize(1);

        Alert alert = alerts.get(0);
        assertThat(alert.getType()).isEqualTo("news_correlation");
        assertThat(alert.getSeverity()).isEqualTo("warning");
        assertThat(alert.getMarketId()).isEqualTo(TEST_MARKET_ID);
        assertThat(alert.getMetadata().get("articleId")).isEqualTo(TEST_ARTICLE_ID);
        assertThat(alert.getMetadata().get("score")).isNotEmpty();

        // Also verify market_news_matches row was written
        List<MarketNewsMatch> matches = fetchMatchesForTestMarket();
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getArticleId()).isEqualTo(TEST_ARTICLE_ID);
    }

    /**
     * Test B — low-volume market must produce zero alerts even when the score
     * exceeds the threshold.
     */
    @Test
    void newsCorrelationSkipsLowVolumeMarket() {
        Market market = new Market();
        market.setMarketId(TEST_MARKET_ID);
        market.setQuestion("Will the test election result happen?");
        market.setVolume24h("50000");     // below 100k threshold
        market.setKeywords(Set.of("test", "election", "result"));
        marketsTable.putItem(market);

        Article article = new Article();
        article.setArticleId(TEST_ARTICLE_ID);
        article.setTitle("Test election result announced");
        article.setUrl("https://example.com/test-election");
        article.setKeywords(Set.of("test", "election", "result", "winner", "poll"));
        articlesTable.putItem(article);

        newsCorrelationDetector.clearCache();
        newsCorrelationDetector.checkMarkets(article);

        assertThat(fetchAlertsForTestMarket()).isEmpty();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Alert> fetchAlertsForTestMarket() {
        List<Alert> result = new ArrayList<>();
        for (Page<Alert> page : alertsTable
                .index("marketId-createdAt-index")
                .query(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(TEST_MARKET_ID).build()))) {
            result.addAll(page.items());
        }
        return result;
    }

    private List<MarketNewsMatch> fetchMatchesForTestMarket() {
        List<MarketNewsMatch> result = new ArrayList<>();
        for (Page<MarketNewsMatch> page : matchesTable.query(
                QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(TEST_MARKET_ID).build()))) {
            result.addAll(page.items());
        }
        return result;
    }
}
