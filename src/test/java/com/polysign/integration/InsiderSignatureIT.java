package com.polysign.integration;

import com.polysign.backtest.ResolutionSweeper;
import com.polysign.backtest.SnapshotArchiver;
import com.polysign.common.AppClock;
import com.polysign.detector.InsiderSignatureDetector;
import com.polysign.detector.PriceMovementDetector;
import com.polysign.detector.StatisticalAnomalyDetector;
import com.polysign.metrics.SignalQualityMetrics;
import com.polysign.metrics.SqsQueueMetrics;
import com.polysign.model.Alert;
import com.polysign.model.Market;
import com.polysign.model.WalletTrade;
import com.polysign.notification.NotificationConsumer;
import com.polysign.poller.MarketPoller;
import com.polysign.poller.PricePoller;
import com.polysign.poller.WalletPoller;
import com.polysign.wallet.WalletMetadata;
import com.polysign.wallet.WalletMetadataService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

/**
 * Integration test for {@link InsiderSignatureDetector}.
 *
 * <p>Runs the real detector against LocalStack DynamoDB. WalletMetadataService is mocked
 * to avoid real API calls. Verifies:
 * <ol>
 *   <li>A qualifying burner-wallet BUY trade fires an alert.</li>
 *   <li>A hedged wallet (bought both YES and NO) does NOT fire an alert.</li>
 * </ol>
 *
 * <p>Extends nothing — defines its own @DynamicPropertySource that reuses the singleton
 * LocalStack container from {@link AbstractIntegrationIT}. All other scheduled beans
 * are mocked out to prevent background activity.
 */
@SpringBootTest
@ActiveProfiles("local")
@MockBean({
        MarketPoller.class,
        PricePoller.class,
        WalletPoller.class,
        NotificationConsumer.class,
        SqsQueueMetrics.class,
        SignalQualityMetrics.class,
        SnapshotArchiver.class,
        ResolutionSweeper.class,
        PriceMovementDetector.class,
        StatisticalAnomalyDetector.class
})
class InsiderSignatureIT {

    static final String TEST_MARKET_ID = "test-insider-it-market";
    static final String BURNER_ADDRESS = "0xburnerinsiderit";
    static final String HEDGER_ADDRESS = "0xhedgerinsiderit";

    /** Reuse the singleton LocalStack container from AbstractIntegrationIT. */
    @DynamicPropertySource
    static void overrideAwsEndpoint(DynamicPropertyRegistry registry) {
        registry.add("aws.endpoint-override",
                () -> AbstractIntegrationIT.LOCAL_STACK.getEndpointOverride(
                        org.testcontainers.containers.localstack.LocalStackContainer.Service.DYNAMODB).toString());
    }

    /** Real detector — NOT mocked. */
    @Autowired InsiderSignatureDetector insiderSignatureDetector;

    /** Mocked to avoid hitting the real Polymarket API. */
    @MockBean WalletMetadataService walletMetadataService;

    @Autowired DynamoDbTable<Market>       marketsTable;
    @Autowired DynamoDbTable<WalletTrade>  walletTradesTable;
    @Autowired DynamoDbTable<Alert>        alertsTable;
    @Autowired DynamoDbTable<WalletMetadata> walletMetadataTable;
    @Autowired AppClock                    appClock;

    private Instant now;

    @BeforeEach
    void setup() {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        // Seed market
        Market market = new Market();
        market.setMarketId(TEST_MARKET_ID);
        market.setQuestion("Will the insider win?");
        market.setVolume24h("500000");
        market.setConditionId("0xtest-insider-it");
        market.setCurrentYesPrice(BigDecimal.valueOf(0.50));
        market.setActive(true);
        market.setAcceptingOrders(true);
        market.setEventSlug("test-insider-it-event");
        marketsTable.putItem(market);

        // Burner wallet metadata (fresh — 5 days old)
        WalletMetadata burnerMeta = new WalletMetadata();
        burnerMeta.setAddress(BURNER_ADDRESS);
        burnerMeta.setFirstTradeAt(now.minus(Duration.ofDays(5)).toString());
        burnerMeta.setLifetimeTradeCount(3);
        burnerMeta.setLifetimeVolumeUsd(BigDecimal.valueOf(20_000));
        burnerMeta.setExpiresAt(now.plus(Duration.ofHours(6)).getEpochSecond());
        walletMetadataTable.putItem(burnerMeta);
        when(walletMetadataService.get(BURNER_ADDRESS)).thenReturn(burnerMeta);

        // Hedger wallet metadata (also fresh, to satisfy burner check before hedge check)
        WalletMetadata hedgerMeta = new WalletMetadata();
        hedgerMeta.setAddress(HEDGER_ADDRESS);
        hedgerMeta.setFirstTradeAt(now.minus(Duration.ofDays(3)).toString());
        hedgerMeta.setLifetimeTradeCount(5);
        hedgerMeta.setLifetimeVolumeUsd(BigDecimal.valueOf(40_000));
        hedgerMeta.setExpiresAt(now.plus(Duration.ofHours(6)).getEpochSecond());
        when(walletMetadataService.get(HEDGER_ADDRESS)).thenReturn(hedgerMeta);

        // Qualifying burner trade: BUY YES $15K (recent — within last run window)
        WalletTrade burnerTrade = new WalletTrade();
        burnerTrade.setAddress(BURNER_ADDRESS);
        burnerTrade.setTxHash("0xtx-burner-yes");
        burnerTrade.setTimestamp(now.minus(Duration.ofSeconds(30)).toString());
        burnerTrade.setMarketId(TEST_MARKET_ID);
        burnerTrade.setSide("BUY");
        burnerTrade.setOutcome("YES");
        burnerTrade.setSizeUsdc(BigDecimal.valueOf(15_000));
        burnerTrade.setPrice(BigDecimal.valueOf(0.50));
        walletTradesTable.putItem(burnerTrade);

        // Hedger trades: BUY YES $15K + BUY NO $15K — both sides covered
        WalletTrade hedgerYes = new WalletTrade();
        hedgerYes.setAddress(HEDGER_ADDRESS);
        hedgerYes.setTxHash("0xtx-hedger-yes");
        hedgerYes.setTimestamp(now.minus(Duration.ofSeconds(60)).toString());
        hedgerYes.setMarketId(TEST_MARKET_ID);
        hedgerYes.setSide("BUY");
        hedgerYes.setOutcome("YES");
        hedgerYes.setSizeUsdc(BigDecimal.valueOf(15_000));
        hedgerYes.setPrice(BigDecimal.valueOf(0.50));
        walletTradesTable.putItem(hedgerYes);

        WalletTrade hedgerNo = new WalletTrade();
        hedgerNo.setAddress(HEDGER_ADDRESS);
        hedgerNo.setTxHash("0xtx-hedger-no");
        hedgerNo.setTimestamp(now.minus(Duration.ofSeconds(45)).toString());
        hedgerNo.setMarketId(TEST_MARKET_ID);
        hedgerNo.setSide("BUY");
        hedgerNo.setOutcome("NO");
        hedgerNo.setSizeUsdc(BigDecimal.valueOf(15_000));
        hedgerNo.setPrice(BigDecimal.valueOf(0.50));
        walletTradesTable.putItem(hedgerNo);

        // Set lastRanAtIso to 5 minutes ago so all seeded trades are picked up
        insiderSignatureDetector.setLastRanAtIso(now.minus(Duration.ofMinutes(5)).toString());
    }

    @AfterEach
    void cleanup() {
        // Delete test market
        marketsTable.deleteItem(Key.builder().partitionValue(TEST_MARKET_ID).build());

        // Delete seeded wallet trades
        for (String[] kv : new String[][]{
                {BURNER_ADDRESS, "0xtx-burner-yes"},
                {HEDGER_ADDRESS, "0xtx-hedger-yes"},
                {HEDGER_ADDRESS, "0xtx-hedger-no"}}) {
            try {
                walletTradesTable.deleteItem(
                        Key.builder().partitionValue(kv[0]).sortValue(kv[1]).build());
            } catch (Exception ignored) {}
        }

        // Delete wallet metadata
        try { walletMetadataTable.deleteItem(Key.builder().partitionValue(BURNER_ADDRESS).build()); } catch (Exception ignored) {}
        try { walletMetadataTable.deleteItem(Key.builder().partitionValue(HEDGER_ADDRESS).build()); } catch (Exception ignored) {}

        // Delete any insider_signature alerts for the test market
        alertsTable.scan().items().stream()
                .filter(a -> TEST_MARKET_ID.equals(a.getMarketId())
                          && "insider_signature".equals(a.getType()))
                .forEach(a -> {
                    try {
                        alertsTable.deleteItem(Key.builder()
                                .partitionValue(a.getAlertId())
                                .sortValue(a.getCreatedAt())
                                .build());
                    } catch (Exception ignored) {}
                });
    }

    @Test
    void insiderSignature_burnerTradeFiresAlert_hedgedTradeDoesNot() {
        // Run the detector
        insiderSignatureDetector.run();

        // Collect insider_signature alerts for the test market
        List<Alert> insiderAlerts = alertsTable.scan().items().stream()
                .filter(a -> TEST_MARKET_ID.equals(a.getMarketId()))
                .filter(a -> "insider_signature".equals(a.getType()))
                .toList();

        // Exactly one alert should be fired — for the burner wallet, not the hedger
        assertThat(insiderAlerts).hasSize(1);
        Alert alert = insiderAlerts.get(0);
        assertThat(alert.getSeverity()).isEqualTo("critical");
        assertThat(alert.getMetadata()).containsEntry("traderAddress", BURNER_ADDRESS);
        assertThat(alert.getMetadata()).containsEntry("outcomeSide", "YES");
        assertThat(alert.getLink()).contains("test-insider-it-event");
        assertThat(alert.getPriceAtAlert())
                .as("priceAtAlert must be persisted to DynamoDB and equal the market's currentYesPrice")
                .isNotNull()
                .isEqualByComparingTo("0.50");
    }
}
