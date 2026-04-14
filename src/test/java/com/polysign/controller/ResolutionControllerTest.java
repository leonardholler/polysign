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
 * § Endpoint tests — verify DynamoDB wiring, filtering, ordering, and enrichment.
 * § groupByMarket tests — pure logic unit tests on the grouping method.
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

    // ════════════════════════════════════════════════════════════════════════
    // Endpoint tests
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void emptyList_whenTableHasNoResolutionRows() {
        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of());

        List<ResolutionController.ResolvedMarketCard> result = controller.getRecentResolutions(20);

        assertThat(result).isEmpty();
    }

    @Test
    void nonResolutionRows_areExcluded() {
        AlertOutcome t1h = outcomeOf("alert-a", "mkt-1", "t1h",        "2026-04-10T10:00:00Z", true);
        AlertOutcome t24 = outcomeOf("alert-b", "mkt-2", "t24h",       "2026-04-10T11:00:00Z", true);
        AlertOutcome res = outcomeOf("alert-c", "mkt-3", "resolution",  "2026-04-10T12:00:00Z", true);

        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of(t1h, t24, res));

        List<ResolutionController.ResolvedMarketCard> result = controller.getRecentResolutions(20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).alerts()).hasSize(1);
        assertThat(result.get(0).alerts().get(0).alertId()).isEqualTo("alert-c");
    }

    @Test
    void cards_sortedByResolvedAtDescending() {
        AlertOutcome older  = outcomeOf("alert-1", "mkt-1", "resolution", "2026-04-09T08:00:00Z", true);
        AlertOutcome newest = outcomeOf("alert-2", "mkt-2", "resolution", "2026-04-10T14:00:00Z", true);
        AlertOutcome middle = outcomeOf("alert-3", "mkt-3", "resolution", "2026-04-10T10:00:00Z", true);

        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of(older, newest, middle));

        List<ResolutionController.ResolvedMarketCard> result = controller.getRecentResolutions(20);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).alerts().get(0).alertId()).isEqualTo("alert-2"); // newest first
        assertThat(result.get(1).alerts().get(0).alertId()).isEqualTo("alert-3");
        assertThat(result.get(2).alerts().get(0).alertId()).isEqualTo("alert-1"); // oldest last
    }

    @Test
    void limit_appliedBeforeGrouping() {
        AlertOutcome r1 = outcomeOf("alert-1", "mkt-1", "resolution", "2026-04-09T01:00:00Z", true);
        AlertOutcome r2 = outcomeOf("alert-2", "mkt-2", "resolution", "2026-04-09T02:00:00Z", true);
        AlertOutcome r3 = outcomeOf("alert-3", "mkt-3", "resolution", "2026-04-09T03:00:00Z", true);

        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of(r1, r2, r3));

        List<ResolutionController.ResolvedMarketCard> result = controller.getRecentResolutions(2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).alerts().get(0).alertId()).isEqualTo("alert-3"); // most recent first
        assertThat(result.get(1).alerts().get(0).alertId()).isEqualTo("alert-2");
    }

    @Test
    void marketTitle_populatedFromMarketTable() {
        AlertOutcome res = outcomeOf("alert-c", "mkt-x", "resolution", "2026-04-10T12:00:00Z", true);
        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of(res));

        Market market = new Market();
        market.setMarketId("mkt-x");
        market.setQuestion("Will the test pass?");
        when(marketsTable.getItem(any(Key.class))).thenReturn(market);

        List<ResolutionController.ResolvedMarketCard> result = controller.getRecentResolutions(20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).marketTitle()).isEqualTo("Will the test pass?");
    }

    @Test
    void marketTitle_nullWhenMarketNotFound() {
        AlertOutcome res = outcomeOf("alert-c", "mkt-x", "resolution", "2026-04-10T12:00:00Z", true);
        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of(res));
        when(marketsTable.getItem(any(Key.class))).thenReturn(null);

        List<ResolutionController.ResolvedMarketCard> result = controller.getRecentResolutions(20);

        assertThat(result).hasSize(1);
        // marketQuestion=null, title=null (alert not found via deep stub) → marketTitle=null
        assertThat(result.get(0).marketTitle()).isNull();
    }

    @Test
    void marketTitle_truncatedAt80Chars() {
        AlertOutcome res = outcomeOf("alert-c", "mkt-x", "resolution", "2026-04-10T12:00:00Z", true);
        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of(res));

        Market market = new Market();
        market.setMarketId("mkt-x");
        market.setQuestion("A".repeat(100));
        when(marketsTable.getItem(any(Key.class))).thenReturn(market);

        List<ResolutionController.ResolvedMarketCard> result = controller.getRecentResolutions(20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).marketTitle()).hasSize(81); // 80 chars + "…"
        assertThat(result.get(0).marketTitle()).endsWith("…");
    }

    @Test
    void http_getRecentResolutions_returns200WithJsonArray() throws Exception {
        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of());

        mvc.perform(get("/api/resolutions/recent").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void priceAtAlert_fromAlertRow_appearsInAlertRowDto() {
        AlertOutcome res = outcomeOf("alert-x", "mkt-x", "resolution", "2026-04-10T12:00:00Z", true);
        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of(res));

        Alert alert = new Alert();
        alert.setAlertId("alert-x");
        alert.setTitle("Price spike alert");
        alert.setLink("https://polymarket.com/event/test");
        alert.setPriceAtAlert(new BigDecimal("0.63"));
        when(alertsTable.query(any(QueryConditional.class)).items().stream())
                .thenReturn(Stream.of(alert));

        List<ResolutionController.ResolvedMarketCard> result = controller.getRecentResolutions(20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).alerts()).hasSize(1);
        assertThat(result.get(0).alerts().get(0).priceAtAlert())
                .as("priceAtAlert should come from the Alert row")
                .isEqualByComparingTo("0.63");
    }

    @Test
    void priceAtAlert_isNull_forPreDeployAlerts() {
        AlertOutcome res = outcomeOf("alert-y", "mkt-y", "resolution", "2026-04-10T12:00:00Z", true);
        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of(res));

        Alert alert = new Alert();
        alert.setAlertId("alert-y");
        alert.setTitle("Pre-deploy alert");
        // priceAtAlert intentionally NOT set → null; no fallback in controller
        when(alertsTable.query(any(QueryConditional.class)).items().stream())
                .thenReturn(Stream.of(alert));

        List<ResolutionController.ResolvedMarketCard> result = controller.getRecentResolutions(20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).alerts().get(0).priceAtAlert())
                .as("priceAtAlert is null for pre-deploy alerts — no fallback to AlertOutcome")
                .isNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // groupByMarket unit tests
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void groupByMarket_emptyInput_returnsEmpty() {
        assertThat(ResolutionController.groupByMarket(List.of())).isEmpty();
    }

    @Test
    void groupByMarket_3SameMarket_2CorrectAnd1Wrong_singleCardMarketCorrectTrue() {
        List<ResolutionController.ResolvedOutcome> outcomes = List.of(
                outcome("a", "mkt1", "2026-04-10T01:00:00Z", "2026-04-11T10:00:00Z", true),
                outcome("b", "mkt1", "2026-04-10T03:00:00Z", "2026-04-11T10:00:00Z", false),
                outcome("c", "mkt1", "2026-04-10T02:00:00Z", "2026-04-11T10:00:00Z", true)
        );

        List<ResolutionController.ResolvedMarketCard> cards =
                ResolutionController.groupByMarket(outcomes);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).marketCorrect()).isTrue();
        assertThat(cards.get(0).alerts()).hasSize(3);
        // alerts sorted by firedAt ASC: a (T+1), c (T+2), b (T+3)
        assertThat(cards.get(0).alerts().get(0).alertId()).isEqualTo("a");
        assertThat(cards.get(0).alerts().get(1).alertId()).isEqualTo("c");
        assertThat(cards.get(0).alerts().get(2).alertId()).isEqualTo("b");
    }

    @Test
    void groupByMarket_3SameMarket_allIncorrect_singleCardMarketCorrectFalse() {
        List<ResolutionController.ResolvedOutcome> outcomes = List.of(
                outcome("a", "mkt1", "2026-04-10T01:00:00Z", "2026-04-11T10:00:00Z", false),
                outcome("b", "mkt1", "2026-04-10T02:00:00Z", "2026-04-11T10:00:00Z", false),
                outcome("c", "mkt1", "2026-04-10T03:00:00Z", "2026-04-11T10:00:00Z", false)
        );

        List<ResolutionController.ResolvedMarketCard> cards =
                ResolutionController.groupByMarket(outcomes);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).marketCorrect()).isFalse();
        assertThat(cards.get(0).alerts()).hasSize(3);
    }

    @Test
    void groupByMarket_3distinctMarkets_3cardsOneAlertEach() {
        List<ResolutionController.ResolvedOutcome> outcomes = List.of(
                outcome("a", "mkt1", "2026-04-10T01:00:00Z", "2026-04-11T10:00:00Z", true),
                outcome("b", "mkt2", "2026-04-10T02:00:00Z", "2026-04-11T11:00:00Z", false),
                outcome("c", "mkt3", "2026-04-10T03:00:00Z", "2026-04-11T12:00:00Z", true)
        );

        List<ResolutionController.ResolvedMarketCard> cards =
                ResolutionController.groupByMarket(outcomes);

        assertThat(cards).hasSize(3);
        assertThat(cards).allSatisfy(card -> assertThat(card.alerts()).hasSize(1));
    }

    @Test
    void groupByMarket_mixed5OutcomesAcross3Markets_correctnessPerMarket() {
        // mkt1: 3 outcomes, all wrong; mkt2: 1 correct; mkt3: 1 correct
        // Input sorted by evaluatedAt desc (as the endpoint would pass them)
        List<ResolutionController.ResolvedOutcome> outcomes = List.of(
                outcome("a1", "mkt1", "2026-04-10T01:00:00Z", "2026-04-11T10:00:00Z", false),
                outcome("a2", "mkt1", "2026-04-10T02:00:00Z", "2026-04-11T10:00:00Z", false),
                outcome("a3", "mkt1", "2026-04-10T03:00:00Z", "2026-04-11T10:00:00Z", false),
                outcome("b1", "mkt2", "2026-04-10T04:00:00Z", "2026-04-11T11:00:00Z", true),
                outcome("c1", "mkt3", "2026-04-10T05:00:00Z", "2026-04-11T12:00:00Z", true)
        );

        List<ResolutionController.ResolvedMarketCard> cards =
                ResolutionController.groupByMarket(outcomes);

        // 3 cards sorted by evaluatedAt desc: mkt3 (T+12), mkt2 (T+11), mkt1 (T+10)
        assertThat(cards).hasSize(3);
        assertThat(cards.get(0).marketId()).isEqualTo("mkt3");
        assertThat(cards.get(0).marketCorrect()).isTrue();
        assertThat(cards.get(1).marketId()).isEqualTo("mkt2");
        assertThat(cards.get(1).marketCorrect()).isTrue();
        assertThat(cards.get(2).marketId()).isEqualTo("mkt1");
        assertThat(cards.get(2).marketCorrect()).isFalse();
        assertThat(cards.get(2).alerts()).hasSize(3);
    }

    @Test
    void groupByMarket_cardOrdering_latestEvaluatedAtFirst() {
        List<ResolutionController.ResolvedOutcome> outcomes = List.of(
                outcome("a", "mkt1", "2026-04-10T01:00:00Z", "2026-04-11T08:00:00Z", true),
                outcome("b", "mkt2", "2026-04-10T01:00:00Z", "2026-04-12T09:00:00Z", false)
        );

        List<ResolutionController.ResolvedMarketCard> cards =
                ResolutionController.groupByMarket(outcomes);

        assertThat(cards).hasSize(2);
        assertThat(cards.get(0).marketId()).isEqualTo("mkt2"); // later evaluatedAt first
        assertThat(cards.get(1).marketId()).isEqualTo("mkt1");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    /** Build an AlertOutcome stub (for endpoint tests that go through DynamoDB mocks). */
    private static AlertOutcome outcomeOf(
            String alertId, String marketId, String horizon, String evaluatedAt, boolean correct) {
        AlertOutcome o = new AlertOutcome();
        o.setAlertId(alertId);
        o.setHorizon(horizon);
        o.setEvaluatedAt(evaluatedAt);
        o.setMarketId(marketId);
        o.setFiredAt("2026-04-08T00:00:00Z");
        o.setType("price_movement");
        o.setPriceAtAlert(new BigDecimal("0.55"));
        o.setPriceAtHorizon(new BigDecimal("1.00"));
        o.setDirectionPredicted(correct ? "up" : "down");
        o.setDirectionRealized("up");
        return o;
    }

    /** Build a ResolvedOutcome for groupByMarket unit tests. */
    private static ResolutionController.ResolvedOutcome outcome(
            String alertId, String marketId, String firedAt, String evaluatedAt, boolean correct) {
        return new ResolutionController.ResolvedOutcome(
                alertId,
                marketId,
                firedAt,
                evaluatedAt,
                correct ? "up" : "down",
                "up",   // realized — correct when matches predicted
                new BigDecimal("0.50"),
                new BigDecimal("1.00"),
                "price_movement",
                "title-" + alertId,
                "https://polymarket.com/" + alertId,
                "Market Q for " + marketId,
                correct);
    }
}
