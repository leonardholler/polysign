package com.polysign.controller;

import com.polysign.model.Alert;
import com.polysign.model.AlertOutcome;
import com.polysign.model.Market;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Unit tests for {@link ResolutionController}.
 *
 * Verifies:
 * 1. Empty list returned gracefully when alert_outcomes table has no resolution rows.
 * 2. Items sorted by evaluatedAt descending.
 * 3. Non-resolution horizon rows are excluded.
 * 4. marketQuestion is populated from the markets table.
 * 5. marketQuestion is null when market is not found.
 */
@ExtendWith(MockitoExtension.class)
class ResolutionControllerTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    DynamoDbTable<AlertOutcome> alertOutcomesTable;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    DynamoDbTable<Alert> alertsTable;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    DynamoDbTable<Market> marketsTable;

    ResolutionController controller;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        controller = new ResolutionController(alertOutcomesTable, alertsTable, marketsTable);
        mvc = standaloneSetup(controller).build();
    }

    // ── Test 1: empty table ───────────────────────────────────────────────────

    @Test
    void emptyList_whenTableHasNoResolutionRows() {
        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of());

        List<ResolutionController.ResolutionItemDto> result = controller.getRecentResolutions(20);

        assertThat(result).isEmpty();
    }

    // ── Test 2: non-resolution horizons excluded ──────────────────────────────

    @Test
    void nonResolutionRows_areExcluded() {
        AlertOutcome t1h = outcomeOf("alert-a", "t1h",        "2026-04-10T10:00:00Z");
        AlertOutcome t24 = outcomeOf("alert-b", "t24h",       "2026-04-10T11:00:00Z");
        AlertOutcome res = outcomeOf("alert-c", "resolution",  "2026-04-10T12:00:00Z");

        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of(t1h, t24, res));

        List<ResolutionController.ResolutionItemDto> result = controller.getRecentResolutions(20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).alertId()).isEqualTo("alert-c");
    }

    // ── Test 3: items sorted by evaluatedAt descending ────────────────────────

    @Test
    void items_sortedByEvaluatedAtDescending() {
        AlertOutcome older  = outcomeOf("alert-1", "resolution", "2026-04-09T08:00:00Z");
        AlertOutcome newest = outcomeOf("alert-2", "resolution", "2026-04-10T14:00:00Z");
        AlertOutcome middle = outcomeOf("alert-3", "resolution", "2026-04-10T10:00:00Z");

        // Feed in non-sorted order
        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of(older, newest, middle));

        List<ResolutionController.ResolutionItemDto> result = controller.getRecentResolutions(20);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).alertId()).isEqualTo("alert-2"); // newest first
        assertThat(result.get(1).alertId()).isEqualTo("alert-3");
        assertThat(result.get(2).alertId()).isEqualTo("alert-1"); // oldest last
    }

    // ── Test 4: limit is respected ────────────────────────────────────────────

    @Test
    void limit_appliedAfterSort() {
        AlertOutcome r1 = outcomeOf("alert-1", "resolution", "2026-04-09T01:00:00Z");
        AlertOutcome r2 = outcomeOf("alert-2", "resolution", "2026-04-09T02:00:00Z");
        AlertOutcome r3 = outcomeOf("alert-3", "resolution", "2026-04-09T03:00:00Z");

        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of(r1, r2, r3));

        List<ResolutionController.ResolutionItemDto> result = controller.getRecentResolutions(2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).alertId()).isEqualTo("alert-3"); // most recent first
        assertThat(result.get(1).alertId()).isEqualTo("alert-2");
    }

    // ── Test 5: marketQuestion populated from markets table ───────────────────

    @Test
    void marketQuestion_populatedFromMarketTable() {
        AlertOutcome res = outcomeOf("alert-c", "resolution", "2026-04-10T12:00:00Z");
        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of(res));

        Market market = new Market();
        market.setMarketId(res.getMarketId());
        market.setQuestion("Will the test pass?");
        when(marketsTable.getItem(any(Key.class))).thenReturn(market);

        List<ResolutionController.ResolutionItemDto> result = controller.getRecentResolutions(20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).marketQuestion()).isEqualTo("Will the test pass?");
    }

    // ── Test 6: marketQuestion null when market not found ─────────────────────

    @Test
    void marketQuestion_nullWhenMarketNotFound() {
        AlertOutcome res = outcomeOf("alert-c", "resolution", "2026-04-10T12:00:00Z");
        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of(res));
        when(marketsTable.getItem(any(Key.class))).thenReturn(null);

        List<ResolutionController.ResolutionItemDto> result = controller.getRecentResolutions(20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).marketQuestion()).isNull();
    }

    // ── Test 7: long marketQuestion is truncated to 80 chars ─────────────────

    @Test
    void marketQuestion_truncatedAt80Chars() {
        AlertOutcome res = outcomeOf("alert-c", "resolution", "2026-04-10T12:00:00Z");
        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of(res));

        String longQuestion = "A".repeat(100); // 100 chars
        Market market = new Market();
        market.setMarketId(res.getMarketId());
        market.setQuestion(longQuestion);
        when(marketsTable.getItem(any(Key.class))).thenReturn(market);

        List<ResolutionController.ResolutionItemDto> result = controller.getRecentResolutions(20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).marketQuestion()).hasSize(81); // 80 chars + "…"
        assertThat(result.get(0).marketQuestion()).endsWith("…");
    }

    // ── Test 8: HTTP route — GET /api/resolutions/recent?limit=5 returns 200 JSON ──

    @Test
    void http_getRecentResolutions_returns200WithJsonArray() throws Exception {
        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of());

        mvc.perform(get("/api/resolutions/recent").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    // ── Test 9: priceAtAlert populated from the Alert row ────────────────────

    @Test
    void priceAtAlert_fromAlertRow_whenAlertHasPriceAtAlert() {
        AlertOutcome res = outcomeOf("alert-x", "resolution", "2026-04-10T12:00:00Z");
        // AlertOutcome has 0.55 (set in helper), Alert has 0.63 — Alert value wins
        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of(res));

        Alert alert = new Alert();
        alert.setAlertId(res.getAlertId());
        alert.setTitle("Price spike alert");
        alert.setLink("https://polymarket.com/event/test");
        alert.setPriceAtAlert(new BigDecimal("0.63"));
        when(alertsTable.query(any(QueryConditional.class)).items().stream())
                .thenReturn(Stream.of(alert));

        List<ResolutionController.ResolutionItemDto> result = controller.getRecentResolutions(20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).priceAtAlert())
                .as("priceAtAlert should come from the Alert row when it is populated")
                .isEqualByComparingTo("0.63");
        assertThat(result.get(0).title()).isEqualTo("Price spike alert");
    }

    // ── Test 10: priceAtAlert is null for pre-deploy alerts (no fallback) ──────

    @Test
    void priceAtAlert_isNull_whenAlertPriceIsNull() {
        AlertOutcome res = outcomeOf("alert-y", "resolution", "2026-04-10T12:00:00Z");
        // AlertOutcome.priceAtAlert = 0.55 in the helper, but the controller no longer reads it.
        // Alert has null priceAtAlert (pre-deploy row written before the field existed).
        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of(res));

        Alert alert = new Alert();
        alert.setAlertId(res.getAlertId());
        alert.setTitle("Pre-deploy alert");
        // priceAtAlert intentionally NOT set → remains null; no fallback in controller
        when(alertsTable.query(any(QueryConditional.class)).items().stream())
                .thenReturn(Stream.of(alert));

        List<ResolutionController.ResolutionItemDto> result = controller.getRecentResolutions(20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).priceAtAlert())
                .as("priceAtAlert is null for pre-deploy alerts — null is correct, no fallback to AlertOutcome")
                .isNull();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AlertOutcome outcomeOf(String alertId, String horizon, String evaluatedAt) {
        AlertOutcome o = new AlertOutcome();
        o.setAlertId(alertId);
        o.setHorizon(horizon);
        o.setEvaluatedAt(evaluatedAt);
        o.setMarketId("mkt-" + alertId);
        o.setFiredAt("2026-04-08T00:00:00Z");
        o.setType("price_movement");
        o.setPriceAtAlert(new BigDecimal("0.55"));
        o.setPriceAtHorizon(new BigDecimal("1.00"));
        o.setDirectionPredicted("up");
        o.setDirectionRealized("up");
        return o;
    }
}
