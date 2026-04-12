package com.polysign.api.v1;

import com.polysign.api.v1.dto.PaginatedResponse;
import com.polysign.api.v1.dto.SnapshotV1Dto;
import com.polysign.common.AppClock;
import com.polysign.model.PriceSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class SnapshotsV1ControllerTest {

    private DynamoDbTable<PriceSnapshot> snapshotsTable;
    private SnapshotsV1Controller        controller;

    @BeforeEach
    void setUp() {
        snapshotsTable = mock(DynamoDbTable.class);
        controller     = new SnapshotsV1Controller(snapshotsTable, new AppClock());
    }

    @Test
    void missingMarketId_throws400() {
        // marketId is a required @RequestParam; missing it causes MissingServletRequestParameterException
        // but when passed as null/blank directly, we validate in the controller
        assertThatThrownBy(() -> controller.listSnapshots(req(), "  ", null, 100, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("marketId is required");
    }

    @Test
    void happyPath_returnsPaginatedSnapshots() {
        PriceSnapshot s = snapshot("market-1", "2026-04-11T00:00:00Z");
        stubQuery(List.of(s), null);

        PaginatedResponse<SnapshotV1Dto> resp = controller.listSnapshots(req(), "market-1", null, 100, null);

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).marketId()).isEqualTo("market-1");
        assertThat(resp.pagination().hasMore()).isFalse();
    }

    @Test
    void hasMore_whenLastEvaluatedKeyPresent() {
        Map<String, AttributeValue> nextKey = Map.of(
                "marketId",  AttributeValue.fromS("market-1"),
                "timestamp", AttributeValue.fromS("2026-04-11T00:00:00Z"));
        stubQuery(List.of(snapshot("market-1", "2026-04-11T00:00:00Z")), nextKey);

        PaginatedResponse<SnapshotV1Dto> resp = controller.listSnapshots(req(), "market-1", null, 100, null);

        assertThat(resp.pagination().hasMore()).isTrue();
        assertThat(resp.pagination().cursor()).isNotNull();
    }

    @Test
    void invalidCursor_throwsInvalidCursorException() {
        stubQuery(List.of(), null);

        assertThatThrownBy(() -> controller.listSnapshots(req(), "market-1", null, 100, "!!!bad!!!"))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void limitAboveMax_throws400() {
        assertThatThrownBy(() -> controller.listSnapshots(req(), "market-1", null, 501, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void cursorRoundtrip_decodesCorrectly() {
        Map<String, AttributeValue> key = Map.of(
                "marketId",  AttributeValue.fromS("m1"),
                "timestamp", AttributeValue.fromS("2026-04-11T00:00:00Z"));
        String cursor = CursorCodec.encode(key);

        stubQuery(List.of(), null);

        controller.listSnapshots(req(), "market-1", null, 100, cursor);
        verify(snapshotsTable).query(any(QueryEnhancedRequest.class));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubQuery(List<PriceSnapshot> items, Map<String, AttributeValue> nextKey) {
        Page<PriceSnapshot> page = mock(Page.class);
        when(page.items()).thenReturn(items);
        when(page.lastEvaluatedKey()).thenReturn(nextKey);
        PageIterable<PriceSnapshot> result = mock(PageIterable.class);
        when(result.iterator()).thenReturn(List.of(page).iterator());
        when(snapshotsTable.query(any(QueryEnhancedRequest.class))).thenReturn(result);
    }

    private static PriceSnapshot snapshot(String marketId, String timestamp) {
        PriceSnapshot s = new PriceSnapshot();
        s.setMarketId(marketId);
        s.setTimestamp(timestamp);
        s.setYesPrice(BigDecimal.valueOf(0.6));
        s.setNoPrice(BigDecimal.valueOf(0.4));
        return s;
    }

    private static MockHttpServletRequest req() {
        return new MockHttpServletRequest();
    }
}
