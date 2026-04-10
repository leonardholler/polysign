package com.polysign.integration;

import com.polysign.common.AppClock;
import com.polysign.detector.ConsensusDetector;
import com.polysign.detector.PriceMovementDetector;
import com.polysign.detector.StatisticalAnomalyDetector;
import com.polysign.model.Alert;
import com.polysign.model.WalletTrade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link ConsensusDetector} against a Testcontainers LocalStack instance.
 *
 * <p>Extends {@link AbstractIntegrationIT} which manages the shared LocalStack container
 * and mocks all background pollers and consumers. This class additionally mocks the
 * two price-movement detectors to prevent them from scanning the markets table and
 * generating spurious alerts for the test market.
 */
@MockBean({PriceMovementDetector.class, StatisticalAnomalyDetector.class})
class ConsensusDetectorIT extends AbstractIntegrationIT {

    static final String TEST_MARKET_ID = "test-market-consensus-it";

    @Autowired ConsensusDetector          consensusDetector;
    @Autowired DynamoDbTable<WalletTrade> walletTradesTable;
    @Autowired DynamoDbTable<Alert>       alertsTable;
    @Autowired AppClock                   clock;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeEach
    @AfterEach
    void cleanup() {
        for (Page<WalletTrade> page : walletTradesTable
                .index("marketId-timestamp-index")
                .query(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(TEST_MARKET_ID).build()))) {
            for (WalletTrade t : page.items()) {
                walletTradesTable.deleteItem(Key.builder()
                        .partitionValue(t.getAddress())
                        .sortValue(t.getTxHash())
                        .build());
            }
        }
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
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Three distinct wallets buying the same market within the consensus window
     * must produce exactly one critical alert with the correct metadata.
     */
    @Test
    void consensusFiresExactlyOnceOnThreeWallets() {
        Instant now = clock.now();
        seedTrade("addr-A", "tx-A", "BUY", now.minus(Duration.ofMinutes(4)));
        seedTrade("addr-B", "tx-B", "BUY", now.minus(Duration.ofMinutes(3)));
        WalletTrade t3 = seedTrade("addr-C", "tx-C", "BUY", now.minus(Duration.ofMinutes(2)));

        consensusDetector.checkConsensus(t3, "test-slug");

        List<Alert> alerts = fetchAlertsForTestMarket();
        assertThat(alerts).hasSize(1);
        Alert alert = alerts.get(0);
        assertThat(alert.getSeverity()).isEqualTo("critical");
        assertThat(alert.getType()).isEqualTo("consensus");
        assertThat(alert.getMetadata().get("direction")).isEqualTo("BUY");
        assertThat(alert.getMetadata().get("walletCount")).isEqualTo("3");
    }

    /**
     * A fourth wallet buying in the same 30-minute window must NOT produce a
     * second alert — the AlertIdFactory 30-minute bucket deduplicates it.
     */
    @Test
    void consensusIsIdempotentOnFourthWallet() {
        Instant now = clock.now();
        seedTrade("addr-A", "tx-A", "BUY", now.minus(Duration.ofMinutes(4)));
        seedTrade("addr-B", "tx-B", "BUY", now.minus(Duration.ofMinutes(3)));
        WalletTrade t3 = seedTrade("addr-C", "tx-C", "BUY", now.minus(Duration.ofMinutes(2)));
        consensusDetector.checkConsensus(t3, "test-slug");

        WalletTrade t4 = seedTrade("addr-D", "tx-D", "BUY", now.minus(Duration.ofMinutes(1)));
        consensusDetector.checkConsensus(t4, "test-slug");

        assertThat(fetchAlertsForTestMarket())
                .as("Expected exactly 1 alert after 4th wallet (idempotency via 30-min bucket)")
                .hasSize(1);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private WalletTrade seedTrade(String address, String txHash,
                                  String side, Instant timestamp) {
        WalletTrade t = new WalletTrade();
        t.setAddress(address);
        t.setTxHash(txHash);
        t.setMarketId(TEST_MARKET_ID);
        t.setSide(side);
        t.setTimestamp(timestamp.toString());
        t.setSizeUsdc(new BigDecimal("5000.00"));
        t.setPrice(new BigDecimal("0.5"));
        walletTradesTable.putItem(t);
        return t;
    }

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
}
