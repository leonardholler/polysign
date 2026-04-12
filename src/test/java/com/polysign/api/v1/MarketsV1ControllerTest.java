package com.polysign.api.v1;

import com.polysign.api.v1.dto.MarketV1Dto;
import com.polysign.api.v1.dto.PaginatedResponse;
import com.polysign.common.AppClock;
import com.polysign.model.Market;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class MarketsV1ControllerTest {

    private DynamoDbTable<Market> marketsTable;
    private MarketsV1Controller   controller;

    @BeforeEach
    void setUp() {
        marketsTable = mock(DynamoDbTable.class);
        controller   = new MarketsV1Controller(marketsTable, new AppClock());
    }

    @Test
    void happyPath_returnsAllMarkets() {
        Market m = market("m1", "crypto", "100000");
        stubScan(List.of(m), null);

        PaginatedResponse<MarketV1Dto> resp = controller.listMarkets(req(), null, null, 50, null);

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).marketId()).isEqualTo("m1");
    }

    @Test
    void minVolumeFilter_excludesLowVolumeMarkets() {
        Market low  = market("low", "crypto", "5000");
        Market high = market("high", "crypto", "200000");
        stubScan(List.of(low, high), null);

        PaginatedResponse<MarketV1Dto> resp = controller.listMarkets(req(), null, 50000.0, 50, null);

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).marketId()).isEqualTo("high");
    }

    @Test
    void hasMore_whenLastEvaluatedKeyPresent() {
        Map<String, AttributeValue> nextKey = Map.of("marketId", AttributeValue.fromS("m2"));
        stubScan(List.of(market("m1", "crypto", "100000")), nextKey);

        PaginatedResponse<MarketV1Dto> resp = controller.listMarkets(req(), null, null, 50, null);

        assertThat(resp.pagination().hasMore()).isTrue();
        assertThat(resp.pagination().cursor()).isNotNull();
    }

    @Test
    void invalidCursor_throwsInvalidCursorException() {
        stubScan(List.of(), null);

        assertThatThrownBy(() -> controller.listMarkets(req(), null, null, 50, "!!!bad!!!"))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    void limitAboveMax_throws400() {
        assertThatThrownBy(() -> controller.listMarkets(req(), null, null, 201, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void cursorRoundtrip_decodesCorrectly() {
        Map<String, AttributeValue> key = Map.of("marketId", AttributeValue.fromS("m-99"));
        String cursor = CursorCodec.encode(key);
        stubScan(List.of(), null);

        controller.listMarkets(req(), null, null, 50, cursor);
        verify(marketsTable).scan(any(ScanEnhancedRequest.class));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void stubScan(List<Market> items, Map<String, AttributeValue> nextKey) {
        Page<Market> page = mock(Page.class);
        when(page.items()).thenReturn(items);
        when(page.lastEvaluatedKey()).thenReturn(nextKey);
        PageIterable<Market> scan = mock(PageIterable.class);
        when(scan.iterator()).thenReturn(List.of(page).iterator());
        when(marketsTable.scan(any(ScanEnhancedRequest.class))).thenReturn(scan);
    }

    private static Market market(String marketId, String category, String volume24h) {
        Market m = new Market();
        m.setMarketId(marketId);
        m.setCategory(category);
        m.setVolume24h(volume24h);
        m.setCurrentYesPrice(BigDecimal.valueOf(0.5));
        return m;
    }

    private static MockHttpServletRequest req() {
        return new MockHttpServletRequest();
    }
}
