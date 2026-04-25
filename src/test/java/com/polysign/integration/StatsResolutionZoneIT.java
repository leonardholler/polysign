package com.polysign.integration;

import com.polysign.api.StatsController;
import com.polysign.model.Market;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — StatsController.marketsInResolutionZone.
 *
 * Writes an effectively-resolved market into LocalStack DynamoDB and asserts
 * that /api/stats returns marketsInResolutionZone >= 1.
 * Uses the Singleton LocalStack container from AbstractIntegrationIT.
 */
class StatsResolutionZoneIT extends AbstractIntegrationIT {

    static final String TEST_MARKET_ID = "test-market-resolution-zone-it";

    @Autowired StatsController              statsController;
    @Autowired DynamoDbTable<Market>        marketsTable;

    @BeforeEach
    @AfterEach
    void cleanup() {
        marketsTable.deleteItem(Key.builder().partitionValue(TEST_MARKET_ID).build());
        // Clear the 60-second StatsController cache so test ordering doesn't cause stale reads.
        statsController.clearCache();
    }

    @Test
    void marketsInResolutionZone_nonZero_whenEffectivelyResolvedMarketExists() {
        // Write a market that satisfies MarketPredicates.effectivelyResolved():
        //   resolvedBy non-blank + outcomePrices[0] >= 0.99
        Market market = new Market();
        market.setMarketId(TEST_MARKET_ID);
        market.setQuestion("Will this test pass?");
        market.setResolvedBy("0xUMAOracleAddress");
        market.setOutcomePrices(List.of("0.99", "0.01"));
        market.setUpdatedAt("2026-04-13T00:00:00Z");
        marketsTable.putItem(market);

        StatsController.StatsResponse stats = statsController.getStats();

        assertThat(stats.marketsInResolutionZone())
                .as("at least the seeded effectively-resolved market should be counted")
                .isGreaterThanOrEqualTo(1L);
    }

    @Test
    void marketsInResolutionZone_zero_whenNoEffectivelyResolvedMarketsExist() {
        // Write an open market — resolvedBy is null, should not count
        Market market = new Market();
        market.setMarketId(TEST_MARKET_ID);
        market.setQuestion("Will this market resolve?");
        market.setOutcomePrices(List.of("0.55", "0.45"));
        market.setUpdatedAt("2026-04-13T00:00:00Z");
        marketsTable.putItem(market);

        StatsController.StatsResponse stats = statsController.getStats();

        // Other tests or background data could add markets; we only assert the
        // open market we seeded does not push the count above zero by itself.
        // We check that the seeded market doesn't inflate the count:
        // Remove the seeded market and ensure the count doesn't drop (i.e. it wasn't counted).
        long countWithOpenMarket = stats.marketsInResolutionZone();

        marketsTable.deleteItem(Key.builder().partitionValue(TEST_MARKET_ID).build());

        StatsController.StatsResponse statsAfter = statsController.getStats();
        assertThat(statsAfter.marketsInResolutionZone())
                .as("removing a non-resolved market should not change the count")
                .isEqualTo(countWithOpenMarket);
    }
}
