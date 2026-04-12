package com.polysign.api.v1;

import com.polysign.api.v1.dto.AlertV1Dto;
import com.polysign.api.v1.dto.PaginatedResponse;
import com.polysign.common.AppClock;
import com.polysign.model.Alert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class AlertsV1ControllerTest {

    private DynamoDbTable<Alert> alertsTable;
    private AppClock             clock;
    private AlertsV1Controller   controller;

    @BeforeEach
    void setUp() {
        alertsTable = mock(DynamoDbTable.class);
        clock       = new AppClock();
        controller  = new AlertsV1Controller(alertsTable, clock);

        // Default: signal-strength scan returns empty list
        stubSignalScan(List.of());
    }

    @Test
    void happyPath_returnsPaginatedResponse() {
        Alert a1 = alert("a1", "market-1", "price_movement");
        Alert a2 = alert("a2", "market-2", "consensus");
        stubPagedScan(List.of(a1, a2), null);

        PaginatedResponse<AlertV1Dto> resp = controller.listAlerts(req(), null, null, null, null, 50, null);

        assertThat(resp.data()).hasSize(2);
        assertThat(resp.data().get(0).alertId()).isEqualTo("a1");
        assertThat(resp.pagination().hasMore()).isFalse();
        assertThat(resp.pagination().cursor()).isNull();
    }

    @Test
    void hasMore_whenDynamoReturnsLastEvaluatedKey() {
        Map<String, AttributeValue> nextKey = Map.of(
                "alertId",   AttributeValue.fromS("a3"),
                "createdAt", AttributeValue.fromS("2026-04-11T00:00:00Z"));
        stubPagedScan(List.of(alert("a1", "m1", "consensus")), nextKey);

        PaginatedResponse<AlertV1Dto> resp = controller.listAlerts(req(), null, null, null, null, 50, null);

        assertThat(resp.pagination().hasMore()).isTrue();
        assertThat(resp.pagination().cursor()).isNotNull();
    }

    @Test
    void cursorRoundtrip_decodesCorrectly() {
        Map<String, AttributeValue> key = Map.of(
                "alertId",   AttributeValue.fromS("x"),
                "createdAt", AttributeValue.fromS("2026-04-11T00:00:00Z"));
        String cursor = CursorCodec.encode(key);

        stubPagedScan(List.of(), null);

        // Should not throw — cursor is valid
        controller.listAlerts(req(), null, null, null, null, 50, cursor);
        verify(alertsTable).scan(any(ScanEnhancedRequest.class));
    }

    @Test
    void limitAboveMax_throws400() {
        assertThatThrownBy(() -> controller.listAlerts(req(), null, null, null, null, 201, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void limitBelowMin_throws400() {
        assertThatThrownBy(() -> controller.listAlerts(req(), null, null, null, null, 0, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void invalidCursor_throwsInvalidCursorException() {
        stubPagedScan(List.of(), null);

        assertThatThrownBy(() -> controller.listAlerts(req(), null, null, null, null, 50, "!!!invalid!!!"))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void signalStrength_isComputedFromRecentAlerts() {
        // Same market, two different types in last 60 min → signalStrength = 2
        Alert recent1 = alert("r1", "mkt-X", "price_movement");
        recent1.setCreatedAt(java.time.Instant.now().minusSeconds(30).toString());
        Alert recent2 = alert("r2", "mkt-X", "consensus");
        recent2.setCreatedAt(java.time.Instant.now().minusSeconds(60).toString());

        stubSignalScan(List.of(recent1, recent2));

        Alert paged = alert("a1", "mkt-X", "price_movement");
        stubPagedScan(List.of(paged), null);

        PaginatedResponse<AlertV1Dto> resp = controller.listAlerts(req(), null, null, null, null, 50, null);

        assertThat(resp.data().get(0).signalStrength()).isEqualTo(2);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubSignalScan(List<Alert> items) {
        SdkIterable<Alert> sdkItems = items::iterator;
        PageIterable<Alert> scan = mock(PageIterable.class);
        when(scan.items()).thenReturn(sdkItems);
        when(alertsTable.scan()).thenReturn(scan);
    }

    private void stubPagedScan(List<Alert> items, Map<String, AttributeValue> nextKey) {
        Page<Alert> page = mock(Page.class);
        when(page.items()).thenReturn(items);
        when(page.lastEvaluatedKey()).thenReturn(nextKey);
        PageIterable<Alert> scan = mock(PageIterable.class);
        when(scan.iterator()).thenReturn(List.of(page).iterator());
        when(alertsTable.scan(any(ScanEnhancedRequest.class))).thenReturn(scan);
    }

    private static MockHttpServletRequest req() {
        return new MockHttpServletRequest();
    }

    private static Alert alert(String alertId, String marketId, String type) {
        Alert a = new Alert();
        a.setAlertId(alertId);
        a.setMarketId(marketId);
        a.setType(type);
        a.setSeverity("warning");
        a.setCreatedAt(java.time.Instant.now().toString());
        return a;
    }
}
