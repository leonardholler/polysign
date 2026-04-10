package com.polysign.integration;

import com.polysign.backtest.AlertOutcomeEvaluator;
import com.polysign.common.AppClock;
import com.polysign.detector.PriceMovementDetector;
import com.polysign.detector.StatisticalAnomalyDetector;
import com.polysign.model.Alert;
import com.polysign.model.AlertOutcome;
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
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-path integration test — the single most valuable test in the project.
 *
 * <p>Proves the complete signal quality loop end-to-end in one test method:
 * <ol>
 *   <li>Price movement detection fires a real alert (PriceMovementDetector against LocalStack DynamoDB)</li>
 *   <li>Alert is persisted to DynamoDB (idempotency condition confirmed)</li>
 *   <li>Alert is enqueued to SQS alerts-to-notify (queue depth = 1)</li>
 *   <li>Re-running the detector produces no new alert or SQS message (idempotency proof)</li>
 *   <li>AlertOutcomeEvaluator scores the alert at T+15min horizon (wasCorrect = true)</li>
 *   <li>Re-running the evaluator produces no new outcome row (conditional write idempotency)</li>
 * </ol>
 *
 * <h3>Clock strategy</h3>
 * AppClock.setClock() is used to fix time without replacing the bean.
 * T0 = now − 2 hours is the detection instant; the alert's createdAt is T0.
 * T1 = T0 + 20 min is the evaluator's "now"; the t15m horizon (T0+15min) is due
 * because T1 > T0+15min, and createdAt=T0 is within the evaluator's [T1−25h, T1−15min] window.
 *
 * <h3>Mutation proof contract (from spec.md)</h3>
 * Comment out {@code alertService.tryCreate(...)} in PriceMovementDetector and re-run:
 * this test FAILS red (alert count = 0, SQS depth = 0). Revert → GREEN.
 */
@MockBean(StatisticalAnomalyDetector.class)
class GoldenPathIT extends AbstractIntegrationIT {

    static final String TEST_MARKET_ID    = "test-market-golden-path-it";
    static final String NOTIFY_QUEUE_NAME = "alerts-to-notify";

    @Autowired PriceMovementDetector       priceMovementDetector;
    @Autowired AlertOutcomeEvaluator       alertOutcomeEvaluator;
    @Autowired DynamoDbTable<Market>       marketsTable;
    @Autowired DynamoDbTable<PriceSnapshot> snapshotsTable;
    @Autowired DynamoDbTable<Alert>         alertsTable;
    @Autowired DynamoDbTable<AlertOutcome>  alertOutcomesTable;
    @Autowired SqsClient                    sqsClient;
    @Autowired AppClock                     appClock;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeEach
    @AfterEach
    void cleanup() {
        // Restore real clock in case a previous test left it fixed.
        appClock.setClock(Clock.systemUTC());

        // Purge the notify queue so no stale messages affect depth assertions.
        String queueUrl = notifyQueueUrl();
        sqsClient.purgeQueue(r -> r.queueUrl(queueUrl));

        // Delete all alert_outcomes rows for this market's alerts (fetch alertIds first).
        List<Alert> alerts = fetchAlertsForTestMarket();
        for (Alert a : alerts) {
            for (Page<AlertOutcome> page : alertOutcomesTable.query(
                    QueryConditional.keyEqualTo(
                            Key.builder().partitionValue(a.getAlertId()).build()))) {
                for (AlertOutcome o : page.items()) {
                    alertOutcomesTable.deleteItem(Key.builder()
                            .partitionValue(o.getAlertId())
                            .sortValue(o.getHorizon())
                            .build());
                }
            }
            alertsTable.deleteItem(Key.builder()
                    .partitionValue(a.getAlertId())
                    .sortValue(a.getCreatedAt())
                    .build());
        }

        // Delete snapshots for this market (query last 30 days).
        Instant cutoff = Instant.now().minus(Duration.ofDays(30));
        for (Page<PriceSnapshot> page : snapshotsTable.query(
                QueryConditional.sortGreaterThanOrEqualTo(
                        Key.builder()
                                .partitionValue(TEST_MARKET_ID)
                                .sortValue(cutoff.toString())
                                .build()))) {
            for (PriceSnapshot s : page.items()) {
                snapshotsTable.deleteItem(Key.builder()
                        .partitionValue(s.getMarketId())
                        .sortValue(s.getTimestamp())
                        .build());
            }
        }

        // Delete test market.
        marketsTable.deleteItem(Key.builder().partitionValue(TEST_MARKET_ID).build());
    }

    // ── Golden-path test ──────────────────────────────────────────────────────

    /**
     * 13-step golden-path: detect → persist → enqueue → idempotency → outcome evaluation.
     *
     * <p>This is the test spec.md says must never be skipped.
     */
    @Test
    void goldenPath_alertFiresAndOutcomeEvaluates() {

        // ── Clock setup ──────────────────────────────────────────────────────
        // T0 = "now − 2h" is the detection instant.
        // T1 = T0 + 20min is the evaluator's "now"; the t15m horizon is due (20 > 15).
        // alert.createdAt = T0, which is within evaluator window [T1−25h, T1−15min].
        Instant T0 = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofHours(2));
        Instant T1 = T0.plus(Duration.ofMinutes(20));

        // ── Step 1: Seed market ──────────────────────────────────────────────
        Market market = new Market();
        market.setMarketId(TEST_MARKET_ID);
        market.setQuestion("Will the golden-path test pass?");
        market.setVolume24h("100000");   // > 50 000 threshold
        market.setIsWatched(false);
        marketsTable.putItem(market);

        // ── Step 2: Seed 20 flat snapshots at 0.50 ───────────────────────────
        // Placed at T0−20min … T0−1min (one per minute).
        for (int i = 20; i >= 1; i--) {
            seedSnapshot(TEST_MARKET_ID,
                    T0.minus(Duration.ofMinutes(i)),
                    new BigDecimal("0.50"));
        }

        // ── Step 3: Seed spike snapshot at 0.60 at exactly T0 ───────────────
        // Move = (0.60 − 0.50) / 0.50 × 100 = 20% > 8% threshold.
        // 20% ≥ 16% (2× threshold) → bypassDedupe = true → effectiveWindow = Duration.ZERO.
        // createdAt = bucketedInstant(T0, Duration.ZERO) = T0 (raw epoch second).
        seedSnapshot(TEST_MARKET_ID, T0, new BigDecimal("0.60"));

        // ── Step 4: Call detect() with clock fixed to T0 ─────────────────────
        appClock.setClock(Clock.fixed(T0, ZoneOffset.UTC));
        priceMovementDetector.detect();

        // ── Step 5: Assert exactly one alert for this market ─────────────────
        List<Alert> alertsAfterFirstDetect = fetchAlertsForTestMarket();
        assertThat(alertsAfterFirstDetect)
                .as("Exactly one alert must be written after the first detect() call")
                .hasSize(1);
        Alert firedAlert = alertsAfterFirstDetect.get(0);
        assertThat(firedAlert.getType()).isEqualTo("price_movement");
        assertThat(firedAlert.getMarketId()).isEqualTo(TEST_MARKET_ID);
        assertThat(firedAlert.getMetadata().get("direction")).isEqualTo("up");

        // ── Step 6: Assert one SQS message in alerts-to-notify ───────────────
        int depthAfterFirst = notifyQueueDepth();
        assertThat(depthAfterFirst)
                .as("One SQS message must be enqueued after the first detect() call")
                .isEqualTo(1);

        // ── Steps 7 & 8: Re-run detect() — idempotency proof ─────────────────
        // Same clock → same alertId + createdAt → attribute_not_exists condition rejects duplicate.
        priceMovementDetector.detect();

        assertThat(fetchAlertsForTestMarket())
                .as("Re-running detect() must NOT produce a second alert (DynamoDB idempotency)")
                .hasSize(1);
        assertThat(notifyQueueDepth())
                .as("Re-running detect() must NOT enqueue a second SQS message")
                .isEqualTo(1);

        // ── Step 9: Seed snapshot at T0 + 15min with price 0.62 ─────────────
        // priceAtAlert = 0.60 (spike at T0).
        // priceAtHorizon = 0.62.
        // rawDelta = 0.62 − 0.60 = 0.02 > 0.005 → directionRealized = "up".
        // directionPredicted = "up" (from alert metadata).
        // wasCorrect = true.
        Instant horizonInstant = T0.plus(Duration.ofMinutes(15));
        seedSnapshot(TEST_MARKET_ID, horizonInstant, new BigDecimal("0.62"));

        // ── Step 10: Advance clock to T1, call evaluate() ────────────────────
        appClock.setClock(Clock.fixed(T1, ZoneOffset.UTC));
        alertOutcomeEvaluator.evaluate();

        // ── Step 11: Assert one outcome row with wasCorrect = true ───────────
        List<AlertOutcome> outcomes = fetchOutcomesForAlert(firedAlert.getAlertId());
        assertThat(outcomes)
                .as("Exactly one outcome row must be written at the t15m horizon")
                .hasSize(1);
        AlertOutcome outcome = outcomes.get(0);
        assertThat(outcome.getHorizon()).isEqualTo("t15m");
        assertThat(outcome.getWasCorrect())
                .as("wasCorrect must be true: price moved from 0.60 to 0.62 (direction = up, predicted = up)")
                .isTrue();
        assertThat(outcome.getMagnitudePp())
                .as("magnitudePp must be positive (price went in the predicted direction)")
                .isGreaterThan(BigDecimal.ZERO);

        // ── Steps 12 & 13: Re-run evaluate() — idempotency proof ─────────────
        // attribute_not_exists(horizon) condition on alert_outcomes rejects the duplicate write.
        alertOutcomeEvaluator.evaluate();

        assertThat(fetchOutcomesForAlert(firedAlert.getAlertId()))
                .as("Re-running evaluate() must NOT produce a second outcome row (conditional write idempotency)")
                .hasSize(1);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void seedSnapshot(String marketId, Instant timestamp, BigDecimal price) {
        PriceSnapshot s = new PriceSnapshot();
        s.setMarketId(marketId);
        s.setTimestamp(timestamp.toString());
        s.setMidpoint(price);
        s.setYesPrice(price);
        s.setNoPrice(BigDecimal.ONE.subtract(price));
        s.setVolume24h(new BigDecimal("100000"));
        s.setExpiresAt(Instant.now().plus(Duration.ofDays(30)).getEpochSecond());
        snapshotsTable.putItem(s);
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

    private List<AlertOutcome> fetchOutcomesForAlert(String alertId) {
        List<AlertOutcome> result = new ArrayList<>();
        for (Page<AlertOutcome> page : alertOutcomesTable.query(
                QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(alertId).build()))) {
            result.addAll(page.items());
        }
        return result;
    }

    private String notifyQueueUrl() {
        return sqsClient.getQueueUrl(r -> r.queueName(NOTIFY_QUEUE_NAME)).queueUrl();
    }

    /** Returns the approximate (exact in LocalStack) visible message count. */
    private int notifyQueueDepth() {
        Map<QueueAttributeName, String> attrs = sqsClient.getQueueAttributes(r -> r
                .queueUrl(notifyQueueUrl())
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
        ).attributes();
        return Integer.parseInt(attrs.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES));
    }
}
