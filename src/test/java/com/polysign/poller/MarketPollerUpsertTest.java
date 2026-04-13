package com.polysign.poller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.polysign.common.AppClock;
import com.polysign.common.AppStats;
import com.polysign.model.Market;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MarketPoller#doUpsert}.
 *
 * <p>Verifies that:
 * <ol>
 *   <li>currentYesPrice written by PricePoller is preserved across a poll cycle
 *       (Fix for dashboard em-dash bug: putItem() full-replace was erasing it).</li>
 *   <li>Near-resolved markets (previously short-circuited by "Fix 6") now reach
 *       putItem() and have their Phase 13 fields (active, outcomePrices) written,
 *       enabling MarketLivenessGate to block detector fires on decided markets.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class MarketPollerUpsertTest {

    private static final Instant NOW = Instant.parse("2026-04-13T12:00:00Z");

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    DynamoDbTable<Market> marketsTable;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    WebClient webClient;

    private MarketPoller poller;

    @BeforeEach
    void setUp() {
        AppClock clock = new AppClock();
        clock.setClock(Clock.fixed(NOW, ZoneOffset.UTC));

        poller = new MarketPoller(
                webClient,
                marketsTable,
                clock,
                new ObjectMapper(),
                CircuitBreakerRegistry.ofDefaults(),
                RetryRegistry.ofDefaults(),
                new SimpleMeterRegistry(),
                new AppStats(),
                0.0,  // minVolumeUsdc — quality gates disabled for unit tests
                0.0,  // minVolume24hUsdc
                0L,   // minHoursToEnd
                400,  // maxMarkets
                "",   // excludedPatternsCsv
                ""    // excludedCategoriesCsv
        );
    }

    /**
     * currentYesPrice=0.5 stored by PricePoller must survive a MarketPoller cycle.
     *
     * <p>Before the fix, doUpsert() constructed a fresh Market without setting
     * currentYesPrice, then called putItem() (full DynamoDB item replace), erasing
     * PricePoller's value every ~90 seconds.
     */
    @Test
    void currentYesPriceIsPreservedAcrossUpsert() {
        Market existing = new Market();
        existing.setMarketId("mkt-preserve");
        existing.setCurrentYesPrice(new BigDecimal("0.5"));
        when(marketsTable.getItem(any(Key.class))).thenReturn(existing);

        Map<String, Object> item = Map.of(
                "id",          "mkt-preserve",
                "endDate",     "2026-04-20T09:00:00Z",
                "volume",      "500000",
                "volume24hr",  "50000"
        );

        poller.doUpsert(item, false);

        ArgumentCaptor<Market> captor = ArgumentCaptor.forClass(Market.class);
        verify(marketsTable).putItem(captor.capture());
        assertThat(captor.getValue().getCurrentYesPrice())
                .isEqualByComparingTo("0.5");
    }

    /**
     * A near-resolved market (currentYesPrice=0.99 — old Fix 6 threshold) must still
     * reach putItem() and have its Phase 13 fields written.
     *
     * <p>Fix 6 skipped these markets entirely, leaving active=null and
     * acceptingOrders=null so MarketLivenessGate could never block them.
     * After removal, the gate can read the fields and suppress detector fires.
     */
    @Test
    void nearResolvedMarketStillWritesPhase13Fields() {
        Market existing = new Market();
        existing.setMarketId("mkt-near-resolved");
        existing.setCurrentYesPrice(new BigDecimal("0.99"));
        when(marketsTable.getItem(any(Key.class))).thenReturn(existing);

        Map<String, Object> item = Map.of(
                "id",              "mkt-near-resolved",
                "endDate",         "2026-04-20T09:00:00Z",
                "volume",          "500000",
                "volume24hr",      "50000",
                "active",          "false",
                "acceptingOrders", "false",
                "outcomePrices",   "[\"0.9995\",\"0.0005\"]"
        );

        poller.doUpsert(item, false);

        ArgumentCaptor<Market> captor = ArgumentCaptor.forClass(Market.class);
        verify(marketsTable).putItem(captor.capture());

        Market written = captor.getValue();
        assertThat(written.getActive()).isFalse();
        assertThat(written.getOutcomePrices()).containsExactly("0.9995", "0.0005");
        // currentYesPrice is also preserved from DynamoDB
        assertThat(written.getCurrentYesPrice()).isEqualByComparingTo("0.99");
    }
}
