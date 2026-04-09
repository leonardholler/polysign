package com.polysign.detector;

import com.polysign.common.AppClock;
import com.polysign.model.Alert;
import com.polysign.model.WalletTrade;
import com.polysign.poller.MarketPoller;
import com.polysign.poller.PricePoller;
import com.polysign.poller.WalletPoller;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for ConsensusDetector against a live LocalStack DynamoDB.
 *
 * Prerequisites:
 *   1. LocalStack running on localhost:4566
 *      (docker compose up localstack -d)
 *   2. Run with -Dintegration-tests=true
 *
 * Skipped by default to keep {@code mvn test} runnable on a fresh
 * clone with no infrastructure. Phase 10 will formalize
 * integration-test execution via Maven Failsafe and CI.
 */
@EnabledIfSystemProperty(named = "integration-tests", matches = "true")
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = "aws.endpoint-override=http://localhost:4566")
@MockBean({MarketPoller.class, PricePoller.class, WalletPoller.class,
           PriceMovementDetector.class, StatisticalAnomalyDetector.class,
           com.polysign.notification.NotificationConsumer.class})
class ConsensusDetectorIntegrationTest {

    /**
     * Unique per test run — prevents cross-run bleed and enables safe parallel
     * execution against a shared LocalStack.
     */
    static final String TEST_MARKET_ID =
            "test-market-consensus-" + UUID.randomUUID();

    @Autowired ConsensusDetector          consensusDetector;
    @Autowired DynamoDbTable<WalletTrade> walletTradesTable;
    @Autowired DynamoDbTable<Alert>       alertsTable;
    @Autowired AppClock                   clock;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeEach
    @AfterEach
    void cleanup() {
        // Remove any wallet_trades rows for TEST_MARKET_ID via the GSI.
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

        // Remove any alerts rows for TEST_MARKET_ID via the GSI.
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
        WalletTrade t1 = seedTrade("addr-A", "tx-A", "BUY", now.minus(Duration.ofMinutes(4)));
        WalletTrade t2 = seedTrade("addr-B", "tx-B", "BUY", now.minus(Duration.ofMinutes(3)));
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
     * A fourth wallet buying the same market in the same 30-minute window must NOT
     * produce a second alert — the AlertIdFactory 30-minute bucket deduplicates it.
     *
     * CRITICAL: if this test sees count == 2, stop and investigate AlertIdFactory
     * canonicalPayloadHash before changing ConsensusDetector or AlertIdFactory.
     */
    @Test
    void consensusIsIdempotentOnFourthWallet() {
        Instant now = clock.now();
        WalletTrade t1 = seedTrade("addr-A", "tx-A", "BUY", now.minus(Duration.ofMinutes(4)));
        WalletTrade t2 = seedTrade("addr-B", "tx-B", "BUY", now.minus(Duration.ofMinutes(3)));
        WalletTrade t3 = seedTrade("addr-C", "tx-C", "BUY", now.minus(Duration.ofMinutes(2)));
        consensusDetector.checkConsensus(t3, "test-slug");   // fires alert #1

        WalletTrade t4 = seedTrade("addr-D", "tx-D", "BUY", now.minus(Duration.ofMinutes(1)));
        consensusDetector.checkConsensus(t4, "test-slug");   // must NOT fire a second alert

        List<Alert> alerts = fetchAlertsForTestMarket();
        assertThat(alerts)
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

    /** Collects all alerts for TEST_MARKET_ID, paginating fully. */
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
