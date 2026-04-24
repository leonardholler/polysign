package com.polysign.alert;

import com.polysign.common.AppClock;
import com.polysign.common.AppStats;
import com.polysign.model.Alert;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Idempotent alert writer.
 *
 * <p>Writes an {@link Alert} to DynamoDB with an {@code attribute_not_exists(alertId)}
 * condition. If the alert already exists, {@link ConditionalCheckFailedException} is
 * logged at DEBUG and swallowed — this is the normal dedupe path, not an error.
 *
 * <p>On a successful new write, the alert is also enqueued to the
 * {@code alerts-to-notify} SQS queue for downstream notification.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private static final long TTL_SECONDS = 30L * 24 * 60 * 60; // 30 days

    private static final Expression IDEMPOTENCY_CONDITION = Expression.builder()
            .expression("attribute_not_exists(alertId)")
            .build();

    private final DynamoDbTable<Alert> alertsTable;
    private final SqsClient sqsClient;
    private final AppClock clock;
    private final MeterRegistry meterRegistry;
    private final AppStats appStats;
    private final String alertsQueueName;

    // Lazily resolved — BootstrapRunner creates the queue after beans are wired.
    private volatile String alertsQueueUrl;

    public AlertService(DynamoDbTable<Alert> alertsTable,
                        SqsClient sqsClient,
                        AppClock clock,
                        MeterRegistry meterRegistry,
                        AppStats appStats,
                        @Value("${polysign.sqs.queues.alerts-to-notify:alerts-to-notify}") String alertsQueueName) {
        this.alertsTable = alertsTable;
        this.sqsClient = sqsClient;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.appStats = appStats;
        this.alertsQueueName = alertsQueueName;
    }

    /**
     * Attempt to persist a new alert and enqueue it for notification.
     *
     * @param alert fully populated alert (alertId, type, severity, marketId, etc.);
     *              createdAt should be set by the caller to a deterministic value
     *              (see {@link AlertIdFactory#bucketedInstant})
     * @return {@code true} if the alert was new and written; {@code false} if it
     *         already existed (deduplicated)
     */
    public boolean tryCreate(Alert alert) {
        // Fill write-time fields if caller didn't set them
        if (alert.getCreatedAt() == null) {
            alert.setCreatedAt(clock.nowIso());
        }
        if (alert.getExpiresAt() == null) {
            alert.setExpiresAt(clock.nowEpochSeconds() + TTL_SECONDS);
        }
        if (alert.getWasNotified() == null) {
            alert.setWasNotified(false);
        }
        // GSI fields for AlertOutcomeEvaluator — schedule first evaluation at firedAt+15m.
        // evaluationStatus="PENDING" keeps the alert in nextEvaluationDue-index until all
        // horizons are evaluated, after which AlertOutcomeEvaluator removes the attribute.
        if (alert.getEvaluationStatus() == null) {
            alert.setEvaluationStatus("PENDING");
            try {
                java.time.Instant firstEval = java.time.Instant.parse(alert.getCreatedAt())
                        .plus(java.time.Duration.ofMinutes(15));
                alert.setNextEvaluationDue(firstEval.toString());
            } catch (Exception ignored) {
                // malformed createdAt — evaluator will miss this alert; better than crashing
            }
        }

        try {
            alertsTable.putItem(PutItemEnhancedRequest.builder(Alert.class)
                    .item(alert)
                    .conditionExpression(IDEMPOTENCY_CONDITION)
                    .build());
        } catch (ConditionalCheckFailedException e) {
            // Normal path — this alert was already emitted within the dedupe window.
            log.debug("alert_already_exists alertId={} type={} marketId={}",
                    alert.getAlertId(), alert.getType(), alert.getMarketId());
            Counter.builder("polysign.alerts.deduplicated")
                    .tag("type", alert.getType() != null ? alert.getType() : "unknown")
                    .register(meterRegistry)
                    .increment();
            return false;
        }

        String tier = alert.getMetadata() != null
                ? alert.getMetadata().getOrDefault("liquidityTier", "unknown")
                : "unknown";
        log.info("alert_created alertId={} marketId={} type={} tier={} severity={}",
                alert.getAlertId(), alert.getMarketId(), alert.getType(), tier, alert.getSeverity());

        appStats.recordAlertFired(alert.getType());

        Counter.builder("polysign.alerts.fired")
                .tag("type", alert.getType())
                .tag("severity", alert.getSeverity())
                .register(meterRegistry)
                .increment();

        enqueue(alert);
        return true;
    }

    private void enqueue(Alert alert) {
        try {
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(resolveQueueUrl())
                    .messageBody(alert.getAlertId())
                    .build());
            log.info("alert_enqueued alertId={} queue=alerts-to-notify", alert.getAlertId());
        } catch (Exception e) {
            // The alert is already persisted in DynamoDB. A failed enqueue is recoverable:
            // the notification worker can be replayed, or a DynamoDB stream can back-fill.
            log.warn("alert_enqueue_failed alertId={} error={}", alert.getAlertId(), e.getMessage());
        }
    }

    private String resolveQueueUrl() {
        String url = alertsQueueUrl;
        if (url == null) {
            url = sqsClient.getQueueUrl(r -> r.queueName(alertsQueueName)).queueUrl();
            alertsQueueUrl = url;
            log.info("AlertService resolved queue URL: {}", url);
        }
        return url;
    }
}
