package com.polysign.detector;

import com.polysign.alert.AlertService;
import com.polysign.common.AppClock;
import com.polysign.model.Alert;
import com.polysign.model.Article;
import com.polysign.model.Market;
import com.polysign.model.MarketNewsMatch;
import com.polysign.processing.NewsMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link NewsCorrelationDetector} — Claude sentiment path.
 *
 * Uses a testable subclass that overrides {@link NewsCorrelationDetector#getOrLoadMarkets()}
 * to avoid real DynamoDB calls, and injects mocked {@link ClaudeSentimentService}.
 */
@SuppressWarnings("unchecked")
class NewsCorrelationDetectorTest {

    private static final Instant NOW = Instant.parse("2026-04-10T12:00:00Z");

    private AlertService                   alertService;
    private DynamoDbTable<MarketNewsMatch> matchesTable;
    private ClaudeSentimentService         sentimentService;
    private NewsMatcher                    newsMatcher;
    private TestableDetector               detector;

    @BeforeEach
    void setUp() {
        alertService     = mock(AlertService.class);
        matchesTable     = mock(DynamoDbTable.class);
        sentimentService = mock(ClaudeSentimentService.class);
        newsMatcher      = new NewsMatcher(); // real implementation

        when(alertService.tryCreate(any())).thenReturn(true);

        AppClock clock = new AppClock();
        clock.setClock(Clock.fixed(NOW, ZoneOffset.UTC));

        detector = new TestableDetector(alertService, matchesTable, sentimentService, newsMatcher, clock);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Market market(String id, String question) {
        Market m = new Market();
        m.setMarketId(id);
        m.setQuestion(question);
        m.setVolume24h("500000");
        m.setCurrentYesPrice(new BigDecimal("0.60"));
        m.setKeywords(Set.of("trump", "election", "vote", "president"));
        return m;
    }

    private static Article article(String id, String title) {
        Article a = new Article();
        a.setArticleId(id);
        a.setTitle(title);
        a.setSummary("Summary about " + title);
        a.setUrl("https://example.com/" + id);
        a.setKeywords(Set.of("trump", "election", "vote", "white", "house"));
        return a;
    }

    // ── Test 1: bullish Claude sentiment fires alert with YES direction ────────

    @Test
    void claudeBullishSentimentFiresAlertWithYesDirection() {
        Article art = article("a1", "Trump poised for landslide victory in key states");
        Market  mkt = market("m1", "Will Trump win the 2026 election?");

        when(sentimentService.analyze(any(), anyDouble(), any(), any()))
                .thenReturn(new ClaudeSentimentService.SentimentResult(
                        true, +0.82, 0.90, "Article strongly suggests Trump will win"));

        detector.checkMarkets(art);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertService).tryCreate(captor.capture());
        Alert alert = captor.getValue();

        assertThat(alert.getType()).isEqualTo("news_correlation");
        assertThat(alert.getMetadata().get("sentimentDirection")).isEqualTo("YES");
        assertThat(alert.getMetadata().get("sentimentScore")).isEqualTo("0.820");
        assertThat(alert.getMetadata().get("scoringMethod")).isEqualTo("claude");
    }

    // ── Test 2: bearish Claude sentiment fires alert with NO direction ─────────

    @Test
    void claudeBearishSentimentFiresAlertWithNoDirection() {
        Article art = article("a2", "Trump's campaign collapses amid new scandal");
        Market  mkt = market("m1", "Will Trump win the 2026 election?");

        when(sentimentService.analyze(any(), anyDouble(), any(), any()))
                .thenReturn(new ClaudeSentimentService.SentimentResult(
                        true, -0.65, 0.75, "Article suggests Trump is losing ground"));

        detector.checkMarkets(art);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertService).tryCreate(captor.capture());
        Alert alert = captor.getValue();

        assertThat(alert.getMetadata().get("sentimentDirection")).isEqualTo("NO");
        assertThat(alert.getMetadata().get("sentimentScore")).isEqualTo("-0.650");
        assertThat(alert.getMetadata().get("scoringMethod")).isEqualTo("claude");
    }

    // ── Test 3: Claude says not relevant → no alert ───────────────────────────

    @Test
    void claudeNotRelevantProducesNoAlert() {
        Article art = article("a3", "Federal Reserve raises interest rates");
        Market  mkt = market("m1", "Will Trump win the 2026 election?");

        when(sentimentService.analyze(any(), anyDouble(), any(), any()))
                .thenReturn(new ClaudeSentimentService.SentimentResult(
                        false, 0.0, 0.95, "Article about monetary policy, not elections"));

        detector.checkMarkets(art);

        verify(alertService, never()).tryCreate(any());
    }

    // ── Test 4: Claude timeout → fallback to keyword scoring ──────────────────

    @Test
    void claudeTimeoutFallsBackToKeywordScoring() {
        Article art = article("a4", "Trump election victory odds surge");
        Market  mkt = market("m1", "Will Trump win the 2026 election?");

        // Claude unavailable (simulates timeout/circuit open)
        when(sentimentService.analyze(any(), anyDouble(), any(), any())).thenReturn(null);

        // Article and market share 3 keywords ("trump", "election", "vote") — keyword score ≥ 0.75
        detector.checkMarkets(art);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertService).tryCreate(captor.capture());
        Alert alert = captor.getValue();

        assertThat(alert.getMetadata().get("scoringMethod")).isEqualTo("keyword_fallback");
        assertThat(alert.getMetadata()).doesNotContainKey("sentimentDirection");
    }

    // ── Test 5: high keyword score but low |sentiment| → no alert ─────────────
    // Claude overrides keyword noise: article matches keywords but Claude says low signal.

    @Test
    void lowClaudeSentimentBlocksAlertDespiteHighKeywordScore() {
        Article art = article("a5", "Trump attends election vote counting ceremony");
        Market  mkt = market("m1", "Will Trump win the 2026 election?");

        // Claude says relevant but |sentiment|=0.1 < threshold 0.3
        when(sentimentService.analyze(any(), anyDouble(), any(), any()))
                .thenReturn(new ClaudeSentimentService.SentimentResult(
                        true, 0.10, 0.80, "Article is tangentially related but not directional"));

        detector.checkMarkets(art);

        verify(alertService, never()).tryCreate(any());
    }

    // ── Test 6: scoreMarket unit — Claude path ────────────────────────────────

    @Test
    void scoreMarketReturnsCandidateWithClaudeMetadata() {
        Article art = article("a6", "Trump leads polls in key swing states");
        Market  mkt = market("m1", "Will Trump win the 2026 election?");

        when(sentimentService.analyze(any(), anyDouble(), any(), any()))
                .thenReturn(new ClaudeSentimentService.SentimentResult(
                        true, +0.75, 0.88, "Polls favor Trump significantly"));

        NewsCorrelationDetector.Candidate result = detector.scoreMarket(art, mkt);

        assertThat(result).isNotNull();
        assertThat(result.scoringMethod()).isEqualTo("claude");
        assertThat(result.sentimentDirection()).isEqualTo("YES");
        assertThat(result.score()).isCloseTo(0.75, org.assertj.core.api.Assertions.within(0.01));
    }

    // ── Test 7: scoreMarket unit — keyword fallback path ─────────────────────

    @Test
    void scoreMarketKeywordFallbackWhenClaudeUnavailable() {
        Article art = article("a7", "Trump election forecast vote tally");
        Market  mkt = market("m1", "Will Trump win the 2026 election?");

        when(sentimentService.analyze(any(), anyDouble(), any(), any())).thenReturn(null);

        NewsCorrelationDetector.Candidate result = detector.scoreMarket(art, mkt);

        // Keyword score is high enough (shares trump, election, vote) for fallback
        assertThat(result).isNotNull();
        assertThat(result.scoringMethod()).isEqualTo("keyword_fallback");
        assertThat(result.sentimentDirection()).isNull();
    }

    // ── Testable subclass ─────────────────────────────────────────────────────

    static class TestableDetector extends NewsCorrelationDetector {
        private List<Market> markets;

        TestableDetector(AlertService alertService,
                         DynamoDbTable<MarketNewsMatch> matchesTable,
                         ClaudeSentimentService sentimentService,
                         NewsMatcher newsMatcher,
                         AppClock clock) {
            super(null, matchesTable, alertService, newsMatcher, sentimentService, clock,
                  0.75, 100_000.0, 1440, 3, 0.3, 0.3, 0.5);
            // Market with known keywords — market(id, question) helper
            this.markets = List.of(market("m1", "Will Trump win the 2026 election?"));
        }

        void setMarkets(List<Market> markets) { this.markets = markets; }

        @Override
        List<Market> getOrLoadMarkets() { return markets; }

        // Re-expose package-private scoreMarket for direct unit testing
        @Override
        NewsCorrelationDetector.Candidate scoreMarket(Article article, Market market) {
            return super.scoreMarket(article, market);
        }

        private static Market market(String id, String question) {
            Market m = new Market();
            m.setMarketId(id);
            m.setQuestion(question);
            m.setVolume24h("500000");
            m.setCurrentYesPrice(new BigDecimal("0.60"));
            m.setKeywords(Set.of("trump", "election", "vote", "president"));
            return m;
        }
    }
}
