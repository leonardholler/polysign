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
 * Integration test proving that {@link StatisticalAnomalyDetector} stores the PRE-SPIKE price
 * (prevPrice = snapshots.get(n-2)) in {@code Alert.priceAtAlert}, not the spike price
 * (lastPrice = snapshots.get(n-1)).
 *
 * <p>Scenario: 29 flat oscillating snapshots at 0.5000/0.5001 (low stddev baseline),
 * then a spike to 0.60 at T0-1min. The z-score of the spike is >> 3.0 (Tier 1 threshold).
 * Expected: Alert is written with {@code priceAtAlert = 0.5000} (the pre-spike snapshot),
 * confirmed by reading the Alert back from DynamoDB (not the in-memory object).
 */
@MockBean(PriceMovementDetector.class)
class PriceAtAlertStatisticalAnomalyIT extends AbstractIntegrationIT {

    static final String MARKET_ID = "price-at-alert-it-stat-anomaly";

    @Autowired StatisticalAnomalyDetector    statisticalAnomalyDetector;
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
    void priceAtAlert_isPreSpikePrice_notSpikePrice() {
        // T0 is 2 hours in the past; snapshots from T0-30min to T0-1min fall inside
        // the detector's 60-minute lookback window when the clock is fixed to T0.
        Instant T0 = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofHours(2));

        // ── Seed market (Tier 1: volume > $250k) ────────────────────────────────
        Market market = new Market();
        market.setMarketId(MARKET_ID);
        market.setQuestion("Will priceAtAlert store the pre-spike price?");
        market.setVolume24h("300000");
        marketsTable.putItem(market);

        // ── Seed 29 baseline snapshots (T0-30min … T0-2min) at 0.5000/0.5001 ───
        // Alternating prices keep stddev tiny so the spike z-score is large.
        // snapshot[28] = T0-2min (n-2 in the detector, i.e. prevPrice = 0.5000).
        for (int i = 30; i >= 2; i--) {
            String baselinePrice = ((30 - i) % 2 == 0) ? "0.5000" : "0.5001";
            seedSnapshot(T0.minus(Duration.ofMinutes(i)), new BigDecimal(baselinePrice));
        }

        // ── Seed spike snapshot at T0-1min (snapshot[29], lastPrice = 0.60) ─────
        // lastReturn = 0.60 - 0.5000 = 0.10. z-score >> 3.0 (Tier 1 threshold).
        // minDeltaP = 0.03 is satisfied (0.10 > 0.03).
        seedSnapshot(T0.minus(Duration.ofMinutes(1)), new BigDecimal("0.60"));

        // ── Detect ───────────────────────────────────────────────────────────────
        appClock.setClock(Clock.fixed(T0, ZoneOffset.UTC));
        statisticalAnomalyDetector.run();

        // ── Read alert back from DynamoDB (not the in-memory object) ─────────────
        List<Alert> alerts = fetchAlertsForMarket();
        assertThat(alerts)
                .as("Exactly one alert must be written for this market")
                .hasSize(1);

        Alert alert = alerts.get(0);
        assertThat(alert.getType()).isEqualTo("statistical_anomaly");
        assertThat(alert.getMetadata().get("direction")).isEqualTo("up");

        // priceAtAlert must be the pre-spike price (snapshot[28] = 0.5000),
        // NOT the spike price (snapshot[29] = 0.60).
        assertThat(alert.getPriceAtAlert())
                .as("priceAtAlert must equal prevPrice (0.5000), NOT lastPrice (0.60)")
                .isNotNull()
                .isEqualByComparingTo("0.5");
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
