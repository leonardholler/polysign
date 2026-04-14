package com.polysign.integration;

import com.polysign.common.AppClock;
import com.polysign.detector.PriceMovementDetector;
import com.polysign.detector.StatisticalAnomalyDetector;
import com.polysign.model.Alert;
import com.polysign.model.Market;
import com.polysign.model.PriceSnapshot;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test proving that {@link PriceMovementDetector} stores the PRE-MOVE price
 * (fromPrice) in {@code Alert.priceAtAlert}, not the post-move price (toPrice).
 *
 * <p>Scenario: 20 flat snapshots at 0.40, then a drop to 0.05.
 * Move = 87.5% down — well above all tier thresholds.
 * Expected: Alert is written with {@code priceAtAlert = 0.40} (the pre-move price),
 * confirmed by reading the Alert back from DynamoDB (not the in-memory object).
 */
@MockBean(StatisticalAnomalyDetector.class)
class PriceAtAlertPriceMovementIT extends AbstractIntegrationIT {

    static final String MARKET_ID = "price-at-alert-it-price-movement";

    @Autowired PriceMovementDetector         priceMovementDetector;
    @Autowired DynamoDbTable<Market>         marketsTable;
    @Autowired DynamoDbTable<PriceSnapshot>  snapshotsTable;
    @Autowired DynamoDbTable<Alert>          alertsTable;
    @Autowired AppClock                      appClock;

    @BeforeEach
    @AfterEach
    void cleanup() {
        appClock.setClock(Clock.systemUTC());

        List<Alert> alerts = fetchAlertsForMarket();
        for (Alert a : alerts) {
            alertsTable.deleteItem(Key.builder()
                    .partitionValue(a.getAlertId())
                    .sortValue(a.getCreatedAt())
                    .build());
        }

        Instant cutoff = Instant.now().minus(Duration.ofDays(1));
        for (Page<PriceSnapshot> page : snapshotsTable.query(
                QueryConditional.sortGreaterThanOrEqualTo(
                        Key.builder().partitionValue(MARKET_ID).sortValue(cutoff.toString()).build()))) {
            for (PriceSnapshot s : page.items()) {
                snapshotsTable.deleteItem(Key.builder()
                        .partitionValue(s.getMarketId()).sortValue(s.getTimestamp()).build());
            }
        }

        marketsTable.deleteItem(Key.builder().partitionValue(MARKET_ID).build());
    }

    @Test
    void priceAtAlert_isPreMovePrice_notPostMovePrice() {
        Instant T0 = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofHours(1));

        // ── Seed market (Tier 1: volume > $250k) ────────────────────────────────
        Market market = new Market();
        market.setMarketId(MARKET_ID);
        market.setQuestion("Will priceAtAlert store the pre-move price?");
        market.setVolume24h("300000");
        marketsTable.putItem(market);

        // ── Seed 20 flat snapshots at 0.40 (T0-20min … T0-1min) ─────────────────
        for (int i = 20; i >= 1; i--) {
            seedSnapshot(T0.minus(Duration.ofMinutes(i)), new BigDecimal("0.40"));
        }

        // ── Seed drop snapshot at 0.05 at T0 ─────────────────────────────────────
        // Move = (0.40 - 0.05) / 0.40 = 87.5% down — fires on all tiers.
        // move.fromPrice = 0.40, move.toPrice = 0.05.
        seedSnapshot(T0, new BigDecimal("0.05"));

        // ── Detect ───────────────────────────────────────────────────────────────
        appClock.setClock(Clock.fixed(T0, ZoneOffset.UTC));
        priceMovementDetector.detect();

        // ── Read alert back from DynamoDB (not the in-memory object) ─────────────
        List<Alert> alerts = fetchAlertsForMarket();
        assertThat(alerts)
                .as("Exactly one alert must be written for this market")
                .hasSize(1);

        Alert alert = alerts.get(0);
        assertThat(alert.getType()).isEqualTo("price_movement");
        assertThat(alert.getMetadata().get("direction")).isEqualTo("down");
        assertThat(new BigDecimal(alert.getMetadata().get("fromPrice"))).isEqualByComparingTo("0.40");
        assertThat(new BigDecimal(alert.getMetadata().get("toPrice"))).isEqualByComparingTo("0.05");

        assertThat(alert.getPriceAtAlert())
                .as("priceAtAlert must equal fromPrice (0.40), NOT toPrice (0.05)")
                .isNotNull()
                .isEqualByComparingTo("0.40");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void seedSnapshot(Instant timestamp, BigDecimal price) {
        PriceSnapshot s = new PriceSnapshot();
        s.setMarketId(MARKET_ID);
        s.setTimestamp(timestamp.toString());
        s.setMidpoint(price);
        s.setYesPrice(price);
        s.setNoPrice(BigDecimal.ONE.subtract(price));
        s.setVolume24h(new BigDecimal("300000"));
        s.setExpiresAt(Instant.now().plus(Duration.ofDays(30)).getEpochSecond());
        snapshotsTable.putItem(s);
    }

    private List<Alert> fetchAlertsForMarket() {
        List<Alert> result = new ArrayList<>();
        for (Page<Alert> page : alertsTable
                .index("marketId-createdAt-index")
                .query(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(MARKET_ID).build()))) {
            result.addAll(page.items());
        }
        return result;
    }
}
