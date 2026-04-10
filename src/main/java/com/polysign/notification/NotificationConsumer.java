package com.polysign.notification;

import com.polysign.common.CorrelationId;
import com.polysign.model.Alert;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.util.function.Supplier;

/**
 * SQS consumer that drains the {@code alerts-to-notify} queue and pushes each
 * alert to ntfy.sh as a phone notification.
 *
 * <p>Polling model: long-poll (20 s server-side wait) so the thread blocks
 * efficiently when the queue is empty. Spring's {@code fixedDelay} adds a
 * 1-second gap between poll completions, preventing a tight busy-loop when
 * messages flow rapidly.
 *
 * <p>Delivery guarantee: the SQS message is deleted only after ntfy.sh
 * returns success. A failed POST leaves the message visible again after the
 * queue's visibility timeout, allowing up to {@code maxReceiveCount} (5)
 * retries before the message lands in the DLQ.
 *
 * <p>Every ntfy.sh call is wrapped in Resilience4j retry (3 attempts,
 * exponential back-off) + circuit breaker. If ntfy.sh is down the circuit
 * opens after 5 failures and alerts stay queued until it recovers.
 */
@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private static final String NTFY_CB_NAME = "ntfy";
    private static final int SQS_LONG_POLL_SECONDS = 20;
    private static final int SQS_MAX_MESSAGES = 10;

    private final SqsClient sqsClient;
    private final DynamoDbTable<Alert> alertsTable;
    private final WebClient ntfyClient;
    private final MeterRegistry meterRegistry;
    private final PhoneWorthinessFilter phoneWorthinessFilter;
    private final String alertsQueueName;
    private final String ntfyTopic;
    private final CircuitBreaker ntfyCb;
    private final Retry ntfyRetry;

    // Lazily resolved — BootstrapRunner creates the queue after beans are wired.
    // Package-private so test subclasses can pre-set it and bypass lazy resolution.
    volatile String alertsQueueUrl;

    public NotificationConsumer(
            SqsClient sqsClient,
            DynamoDbTable<Alert> alertsTable,
            @Qualifier("ntfyClient") WebClient ntfyClient,
            MeterRegistry meterRegistry,
            PhoneWorthinessFilter phoneWorthinessFilter,
            @Value("${polysign.sqs.queues.alerts-to-notify:alerts-to-notify}") String alertsQueueName,
            @Value("${polysign.ntfy.topic}") String ntfyTopic,
            CircuitBreakerRegistry cbRegistry,
            RetryRegistry retryRegistry) {
        this.sqsClient = sqsClient;
        this.alertsTable = alertsTable;
        this.ntfyClient = ntfyClient;
        this.meterRegistry = meterRegistry;
        this.phoneWorthinessFilter = phoneWorthinessFilter;
        this.alertsQueueName = alertsQueueName;
        this.ntfyTopic = ntfyTopic;
        this.ntfyCb = cbRegistry.circuitBreaker(NTFY_CB_NAME);
        this.ntfyRetry = retryRegistry.retry(NTFY_CB_NAME);
    }

    /**
     * Long-polls the {@code alerts-to-notify} queue and dispatches each message.
     * {@code fixedDelay} ensures at most one concurrent poll cycle.
     */
    @Scheduled(fixedDelayString = "${polysign.consumer.notification.poll-interval-ms:1000}")
    public void poll() {
        var messages = sqsClient.receiveMessage(
                ReceiveMessageRequest.builder()
                        .queueUrl(resolveQueueUrl())
                        .maxNumberOfMessages(SQS_MAX_MESSAGES)
                        .waitTimeSeconds(SQS_LONG_POLL_SECONDS)
                        .build()).messages();

        for (Message msg : messages) {
            process(msg);
        }
    }

    private void process(Message msg) {
        String alertId = msg.body();
        try (var ignored = CorrelationId.set(alertId.substring(0, Math.min(12, alertId.length())))) {
            Alert alert = fetchAlert(alertId);
            if (alert == null) {
                // Stale message — alert was never written or already expired. Clean it up.
                log.warn("notification_alert_not_found alertId={}", alertId);
                deleteMessage(msg);
                return;
            }

            // Evaluate phone-worthiness and persist the result on the alert row.
            boolean worthy = phoneWorthinessFilter.isPhoneWorthy(alert);
            alert.setPhoneWorthy(worthy);
            updatePhoneWorthy(alert);

            if (worthy) {
                boolean posted = doPost(alert);
                if (!posted) {
                    // Leave message in queue — visibility timeout will requeue it.
                    Counter.builder("polysign.notifications.failed")
                            .tag("type", alert.getType() != null ? alert.getType() : "unknown")
                            .register(meterRegistry)
                            .increment();
                    return;
                }
                markNotified(alert);
                log.info("notification_sent alertId={} type={} severity={}",
                        alertId, alert.getType(), alert.getSeverity());
                Counter.builder("polysign.notifications.sent")
                        .tag("type", alert.getType())
                        .tag("severity", alert.getSeverity())
                        .register(meterRegistry)
                        .increment();
            } else {
                log.info("alert_not_phone_worthy alertId={} type={}", alertId, alert.getType());
            }

            // Delete from SQS regardless — the alert is persisted either way.
            deleteMessage(msg);
        } catch (Exception e) {
            log.warn("notification_process_error alertId={} error={}", alertId, e.getMessage());
        }
    }

    /**
     * Looks up an {@link Alert} by its partition key (alertId only).
     * Package-private for test subclass override.
     */
    Alert fetchAlert(String alertId) {
        var pages = alertsTable.query(QueryConditional.keyEqualTo(
                Key.builder().partitionValue(alertId).build()));
        var it = pages.items().iterator();
        return it.hasNext() ? it.next() : null;
    }

    /**
     * Posts the alert to ntfy.sh, wrapped in Retry + CircuitBreaker.
     * Returns {@code true} on success, {@code false} on any failure.
     * Package-private for test subclass override.
     */
    boolean doPost(Alert alert) {
        String body = alert.getDescription() != null ? alert.getDescription() : alert.getTitle();
        try {
            Supplier<Void> call = () -> {
                ntfyClient.post()
                        .uri("/{topic}", ntfyTopic)
                        .header("Title", alert.getTitle())
                        .header("Priority", priority(alert.getSeverity()))
                        .header("Tags", tags(alert.getType()))
                        .bodyValue(body != null ? body : alert.getAlertId())
                        .retrieve()
                        .toBodilessEntity()
                        .block();
                return null;
            };
            Retry.decorateSupplier(ntfyRetry,
                    CircuitBreaker.decorateSupplier(ntfyCb, call)).get();
            return true;
        } catch (Exception e) {
            log.warn("notification_ntfy_failed alertId={} error={}",
                    alert.getAlertId(), e.getMessage());
            return false;
        }
    }

    private void markNotified(Alert alert) {
        try {
            alert.setWasNotified(true);
            alertsTable.updateItem(alert);
        } catch (Exception e) {
            // wasNotified is best-effort — the alert was delivered regardless.
            log.warn("notification_mark_failed alertId={} error={}",
                    alert.getAlertId(), e.getMessage());
        }
    }

    // Package-private so TestableConsumer in tests can override as a no-op.
    void updatePhoneWorthy(Alert alert) {
        try {
            alertsTable.updateItem(alert);
        } catch (Exception e) {
            // phoneWorthy is best-effort metadata — does not affect delivery logic.
            log.warn("notification_phone_worthy_update_failed alertId={} error={}",
                    alert.getAlertId(), e.getMessage());
        }
    }

    private void deleteMessage(Message msg) {
        sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(resolveQueueUrl())
                .receiptHandle(msg.receiptHandle())
                .build());
    }

    private String resolveQueueUrl() {
        String url = alertsQueueUrl;
        if (url == null) {
            url = sqsClient.getQueueUrl(r -> r.queueName(alertsQueueName)).queueUrl();
            alertsQueueUrl = url;
            log.info("NotificationConsumer resolved queue URL: {}", url);
        }
        return url;
    }

    // ── ntfy header helpers ──────────────────────────────────────────────────

    /** Maps alert severity to ntfy priority (1=min, 5=urgent/bypasses DND). */
    static String priority(String severity) {
        return switch (severity != null ? severity : "") {
            case "critical" -> "5";
            case "warning"  -> "3";
            default         -> "2";
        };
    }

    /** Maps alert type to a ntfy tag (renders as an emoji in the notification). */
    static String tags(String type) {
        return switch (type != null ? type : "") {
            case "price_movement"      -> "chart_with_upwards_trend";
            case "statistical_anomaly" -> "bar_chart";
            case "consensus"           -> "busts_in_silhouette";
            case "news_correlation"    -> "newspaper";
            default                    -> "bell";
        };
    }
}
