package com.polysign.notification;

import com.polysign.backtest.SignalPerformanceService;
import com.polysign.common.AppClock;
import com.polysign.model.Alert;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link NotificationConsumer}.
 *
 * <p>All tests use a {@link TestableConsumer} subclass that overrides
 * {@code fetchAlert()} and {@code doPost()} to avoid live DynamoDB / HTTP calls.
 * SQS interactions ({@code receiveMessage}, {@code deleteMessage}) are tested via
 * a mocked {@link SqsClient}.
 */
class NotificationConsumerTest {

    private static final String QUEUE_URL = "http://localhost:4566/000000000000/alerts-to-notify";
    private static final String ALERT_ID  = "845f165cde7828da5dd7e7cc649d3f22";
    private static final String RECEIPT   = "receipt-handle-abc";

    private SqsClient sqsClient;
    @SuppressWarnings("unchecked")
    private DynamoDbTable<Alert> alertsTable;
    private TestableConsumer consumer;

    @BeforeEach
    void setUp() {
        sqsClient   = mock(SqsClient.class);
        alertsTable = mock(DynamoDbTable.class);

        consumer = new TestableConsumer(sqsClient, alertsTable);
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void happyPath_alertFound_ntfySucceeds_messageDeleted() {
        Alert alert = alert(ALERT_ID, "price_movement", "warning");
        consumer.cannedAlert = alert;
        consumer.ntfySuccess = true;

        stubReceive(msg(ALERT_ID, RECEIPT));

        consumer.poll();

        // Message must be deleted
        ArgumentCaptor<DeleteMessageRequest> del = ArgumentCaptor.forClass(DeleteMessageRequest.class);
        verify(sqsClient).deleteMessage(del.capture());
        assertThat(del.getValue().receiptHandle()).isEqualTo(RECEIPT);

        // wasNotified must be flipped on the alert object itself
        assertThat(alert.getWasNotified()).isTrue();
        verify(alertsTable).updateItem(any(Alert.class));

        assertThat(consumer.postCalledWith).isEqualTo(alert);
    }

    // ── Alert not found in DynamoDB ───────────────────────────────────────────

    @Test
    void alertNotFound_messageDeletedWithoutNtfy() {
        consumer.cannedAlert = null; // simulate missing/expired alert
        consumer.ntfySuccess = true;

        stubReceive(msg(ALERT_ID, RECEIPT));

        consumer.poll();

        // Message should still be deleted (stale — no point retrying)
        verify(sqsClient).deleteMessage(any(DeleteMessageRequest.class));
        // ntfy must NOT be called
        assertThat(consumer.postCalledWith).isNull();
        verify(alertsTable, never()).updateItem(any(Alert.class));
    }

    // ── ntfy fails → message left in queue ───────────────────────────────────

    @Test
    void ntfyFails_messageNotDeleted() {
        consumer.cannedAlert = alert(ALERT_ID, "price_movement", "warning");
        consumer.ntfySuccess = false;

        stubReceive(msg(ALERT_ID, RECEIPT));

        consumer.poll();

        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
        verify(alertsTable, never()).updateItem(any(Alert.class));
    }

    // ── Multiple messages in one poll batch ───────────────────────────────────

    @Test
    void multipleMessages_eachProcessedIndependently() {
        Alert a1 = alert("id-001", "price_movement", "warning");
        Alert a2 = alert("id-002", "price_movement", "critical");
        // Consumer returns same alert for both ids (simplification — routing is what's under test)
        consumer.cannedAlert = a1;
        consumer.ntfySuccess = true;

        stubReceive(msg("id-001", "rh-1"), msg("id-002", "rh-2"));

        consumer.poll();

        // Both messages deleted
        verify(sqsClient, times(2)).deleteMessage(any(DeleteMessageRequest.class));
        // updateItem called twice
        verify(alertsTable, times(2)).updateItem(any(Alert.class));
    }

    // ── Empty queue → no SQS deletes ─────────────────────────────────────────

    @Test
    void emptyQueue_noProcessing() {
        stubReceive(); // no messages

        consumer.poll();

        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
        assertThat(consumer.postCalledWith).isNull();
    }

    // ── Priority + tags mapping ───────────────────────────────────────────────

    @Test
    void priorityMapping() {
        assertThat(NotificationConsumer.priority("critical")).isEqualTo("5");
        assertThat(NotificationConsumer.priority("warning")).isEqualTo("3");
        assertThat(NotificationConsumer.priority("info")).isEqualTo("2");
        assertThat(NotificationConsumer.priority(null)).isEqualTo("2");
        assertThat(NotificationConsumer.priority("unknown")).isEqualTo("2");
    }

    @Test
    void tagsMapping() {
        assertThat(NotificationConsumer.tags("price_movement")).isEqualTo("chart_with_upwards_trend");
        assertThat(NotificationConsumer.tags("statistical_anomaly")).isEqualTo("bar_chart");
        assertThat(NotificationConsumer.tags("consensus")).isEqualTo("busts_in_silhouette");
        assertThat(NotificationConsumer.tags("news_correlation")).isEqualTo("newspaper");
        assertThat(NotificationConsumer.tags("unknown")).isEqualTo("bell");
        assertThat(NotificationConsumer.tags(null)).isEqualTo("bell");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubReceive(Message... messages) {
        when(sqsClient.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder()
                        .messages(List.of(messages))
                        .build());
    }

    private static Message msg(String alertId, String receiptHandle) {
        return Message.builder().body(alertId).receiptHandle(receiptHandle).build();
    }

    private static Alert alert(String alertId, String type, String severity) {
        Alert a = new Alert();
        a.setAlertId(alertId);
        a.setCreatedAt("2026-04-09T07:00:00Z");
        a.setType(type);
        a.setSeverity(severity);
        a.setMarketId("market-001");
        a.setTitle("10.0% price move up");
        a.setDescription("Market moved 10.0% up in 5 min (0.5000 → 0.5500)");
        return a;
    }

    /**
     * PhoneWorthinessFilter stub — always phone-worthy.
     * Injected into TestableConsumer so existing delivery tests are unaffected
     * by the worthiness gate (no DynamoDB or SignalPerformanceService needed).
     */
    @SuppressWarnings("unchecked")
    private static class AlwaysWorthyFilter extends PhoneWorthinessFilter {
        AlwaysWorthyFilter() {
            super(mock(DynamoDbTable.class),
                  mock(SignalPerformanceService.class),
                  new AppClock());
        }

        @Override
        public boolean isPhoneWorthy(Alert alert) { return true; }
    }

    /**
     * Testable subclass: overrides {@code fetchAlert}, {@code doPost}, and
     * {@code updatePhoneWorthy} so no live DynamoDB or HTTP calls are made.
     * Captures the alert passed to doPost.
     */
    private static class TestableConsumer extends NotificationConsumer {
        Alert cannedAlert;
        boolean ntfySuccess;
        Alert postCalledWith;

        TestableConsumer(SqsClient sqsClient, DynamoDbTable<Alert> alertsTable) {
            super(sqsClient,
                  alertsTable,
                  null, // ntfyClient — not used (doPost overridden)
                  new SimpleMeterRegistry(),
                  new AlwaysWorthyFilter(),
                  "alerts-to-notify",
                  "polysign-test",
                  CircuitBreakerRegistry.ofDefaults(),
                  RetryRegistry.ofDefaults());
            // Pre-set the queue URL to bypass the lazy getQueueUrl call.
            this.alertsQueueUrl = QUEUE_URL;
        }

        @Override
        Alert fetchAlert(String alertId) {
            return cannedAlert;
        }

        @Override
        boolean doPost(Alert alert) {
            postCalledWith = alert;
            return ntfySuccess;
        }

        // Override to no-op — keeps existing updateItem call counts unchanged.
        @Override
        void updatePhoneWorthy(Alert alert) { }
    }
}
