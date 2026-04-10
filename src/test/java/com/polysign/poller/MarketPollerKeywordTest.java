package com.polysign.poller;

import com.polysign.common.AppClock;
import com.polysign.common.AppStats;
import com.polysign.model.Market;
import com.polysign.processing.KeywordExtractor;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit test confirming that {@link MarketPoller#doUpsert} delegates keyword
 * extraction to {@link KeywordExtractor} and stores the result on the Market.
 */
class MarketPollerKeywordTest {

    @SuppressWarnings("unchecked")
    @Test
    void doUpsertCallsKeywordExtractorAndStoresKeywords() {
        // ── Arrange ───────────────────────────────────────────────────────────
        KeywordExtractor keywordExtractor = mock(KeywordExtractor.class);
        Set<String> expectedKeywords = Set.of("election", "senate", "florida");
        when(keywordExtractor.extract(any())).thenReturn(expectedKeywords);

        DynamoDbTable<Market> marketsTable = mock(DynamoDbTable.class);
        when(marketsTable.getItem(any(Key.class))).thenReturn(null); // no existing market

        MarketPoller poller = new MarketPoller(
                null,                          // WebClient — not called in doUpsert
                marketsTable,
                new AppClock(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.ofDefaults(),
                new SimpleMeterRegistry(),
                keywordExtractor,
                new AppStats(),
                10_000, 10_000, 12, 400
        );

        Map<String, Object> fakeItem = Map.of(
                "id",       "test-market-keyword-001",
                "question", "Will Trump win the Florida Senate election in 2026?",
                "volume",   "500000",
                "volume24hr", "75000"
        );

        // ── Act ───────────────────────────────────────────────────────────────
        poller.doUpsert(fakeItem, false);

        // ── Assert ────────────────────────────────────────────────────────────
        // 1. KeywordExtractor.extract() was called with the market question
        verify(keywordExtractor, times(1))
                .extract("Will Trump win the Florida Senate election in 2026?");

        // 2. The Market stored in DynamoDB has the keywords returned by the extractor
        ArgumentCaptor<Market> captor = ArgumentCaptor.forClass(Market.class);
        verify(marketsTable, times(1)).putItem(captor.capture());

        Market stored = captor.getValue();
        assertThat(stored.getKeywords()).isEqualTo(expectedKeywords);
        assertThat(stored.getMarketId()).isEqualTo("test-market-keyword-001");
    }
}
