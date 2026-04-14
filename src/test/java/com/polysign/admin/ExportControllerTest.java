package com.polysign.admin;

import com.polysign.model.Alert;
import com.polysign.model.AlertOutcome;
import com.polysign.model.Market;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Unit tests for {@link ExportController}.
 *
 * § Auth tests — missing / wrong / correct key.
 * § CSV content tests — row count, column order, RFC 4180 escaping.
 * § Helper unit tests — csvEscape, computeRealizedDirection.
 */
@ExtendWith(MockitoExtension.class)
class ExportControllerTest {

    private static final String TEST_KEY = "test-admin-secret";

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    DynamoDbTable<AlertOutcome> alertOutcomesTable;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    DynamoDbTable<Alert> alertsTable;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    DynamoDbTable<Market> marketsTable;

    ExportController controller;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        controller = new ExportController(
                alertOutcomesTable, alertsTable, marketsTable,
                TEST_KEY, 250_000, 50_000);
        mvc = standaloneSetup(controller).build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Auth
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void missingKey_returns401() throws Exception {
        mvc.perform(get("/admin/export/resolutions.csv"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongKey_returns401() throws Exception {
        mvc.perform(get("/admin/export/resolutions.csv")
                        .header("X-Admin-Key", "wrong-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void correctKey_returns200() throws Exception {
        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.empty());

        mvc.perform(get("/admin/export/resolutions.csv")
                        .header("X-Admin-Key", TEST_KEY))
                .andExpect(status().isOk());
    }

    // ════════════════════════════════════════════════════════════════════════
    // CSV content — 3 alerts + 3 outcomes
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void csv_headerPlusThreeDataRows_commaInTitleEscaped() throws Exception {
        // Three resolution outcomes pointing to the same market (title contains a comma)
        AlertOutcome o1 = outcome("a1", "mkt-x", "price_movement", "2026-04-10T08:00:00Z",
                new BigDecimal("0.45"), new BigDecimal("1.00"), "up", true);
        AlertOutcome o2 = outcome("a2", "mkt-x", "wallet_activity", "2026-04-10T10:00:00Z",
                new BigDecimal("0.50"), new BigDecimal("1.00"), "up", true);
        AlertOutcome o3 = outcome("a3", "mkt-x", "statistical_anomaly", "2026-04-11T09:00:00Z",
                new BigDecimal("0.60"), new BigDecimal("0.00"), "down", false);

        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of(o1, o2, o3));

        // Alerts — each returns its own alert row via thenAnswer (stream consumed once per call)
        Alert alert1 = alert("a1", "warning",  "2026-04-08T07:00:00Z", new BigDecimal("0.45"));
        Alert alert2 = alert("a2", "critical", "2026-04-09T06:00:00Z", new BigDecimal("0.50"));
        Alert alert3 = alert("a3", "info",     "2026-04-10T05:00:00Z", new BigDecimal("0.60"));
        when(alertsTable.query(any(QueryConditional.class)).items().stream())
                .thenAnswer(inv -> Stream.of(alert1))
                .thenAnswer(inv -> Stream.of(alert2))
                .thenAnswer(inv -> Stream.of(alert3));

        // Market with comma in title
        Market market = new Market();
        market.setMarketId("mkt-x");
        market.setQuestion("Will prices, rise by end of April?");
        market.setVolume24h("300000"); // > 250000 → TIER_1
        when(marketsTable.getItem(any(Key.class))).thenReturn(market);

        String csv = mvc.perform(get("/admin/export/resolutions.csv")
                        .header("X-Admin-Key", TEST_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Strip trailing blank line(s) that println adds
        String[] lines = csv.strip().split("\\r?\\n");

        // Header + 3 data rows
        assertThat(lines).hasSize(4);

        // Exact header — column order matches spec
        assertThat(lines[0]).isEqualTo(ExportController.HEADER_ROW);

        // Market title containing a comma must be RFC 4180 quoted
        assertThat(csv).contains("\"Will prices, rise by end of April?\"");

        // Non-resolution rows are absent — scan only returned resolution rows here,
        // but verify none of the data rows contain a horizon indicator as a sanity check
        for (int i = 1; i < lines.length; i++) {
            assertThat(lines[i]).doesNotContain("t15m").doesNotContain("t1h").doesNotContain("t24h");
        }
    }

    @Test
    void nonResolutionRows_areExcluded() throws Exception {
        AlertOutcome t1h = outcome("a1", "mkt-1", "price_movement", "2026-04-10T10:00:00Z",
                new BigDecimal("0.5"), new BigDecimal("1.0"), "up", true);
        t1h.setHorizon("t1h"); // override to non-resolution

        AlertOutcome res = outcome("a2", "mkt-2", "price_movement", "2026-04-10T11:00:00Z",
                new BigDecimal("0.5"), new BigDecimal("1.0"), "up", true);

        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.of(t1h, res));
        when(alertsTable.query(any(QueryConditional.class)).items().stream())
                .thenAnswer(inv -> Stream.empty());
        when(marketsTable.getItem(any(Key.class))).thenReturn(null);

        String csv = mvc.perform(get("/admin/export/resolutions.csv")
                        .header("X-Admin-Key", TEST_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String[] lines = csv.strip().split("\\r?\\n");
        assertThat(lines).hasSize(2); // header + 1 resolution row only
        assertThat(lines[1]).startsWith("a2,");
    }

    @Test
    void emptyTable_onlyHeaderLine() throws Exception {
        when(alertOutcomesTable.scan().items().stream()).thenReturn(Stream.empty());

        String csv = mvc.perform(get("/admin/export/resolutions.csv")
                        .header("X-Admin-Key", TEST_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String[] lines = csv.strip().split("\\r?\\n");
        assertThat(lines).hasSize(1);
        assertThat(lines[0]).isEqualTo(ExportController.HEADER_ROW);
    }

    // ════════════════════════════════════════════════════════════════════════
    // csvEscape unit tests
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void csvEscape_noSpecialChars_unchanged() {
        assertThat(ExportController.csvEscape("simple")).isEqualTo("simple");
    }

    @Test
    void csvEscape_containsComma_wrapped() {
        assertThat(ExportController.csvEscape("hello, world")).isEqualTo("\"hello, world\"");
    }

    @Test
    void csvEscape_containsDoubleQuote_doubled() {
        assertThat(ExportController.csvEscape("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    }

    @Test
    void csvEscape_null_returnsEmpty() {
        assertThat(ExportController.csvEscape(null)).isEmpty();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private static AlertOutcome outcome(String alertId, String marketId, String type,
                                        String evaluatedAt, BigDecimal priceAtAlert,
                                        BigDecimal priceAtHorizon,
                                        String directionPredicted, boolean wasCorrect) {
        AlertOutcome o = new AlertOutcome();
        o.setAlertId(alertId);
        o.setMarketId(marketId);
        o.setHorizon("resolution");
        o.setType(type);
        o.setFiredAt("2026-04-07T00:00:00Z");
        o.setEvaluatedAt(evaluatedAt);
        o.setPriceAtAlert(priceAtAlert);
        o.setPriceAtHorizon(priceAtHorizon);
        o.setDirectionPredicted(directionPredicted);
        o.setDirectionRealized(wasCorrect ? directionPredicted : "down");
        o.setWasCorrect(wasCorrect);
        return o;
    }

    private static Alert alert(String alertId, String severity, String createdAt,
                               BigDecimal priceAtAlert) {
        Alert a = new Alert();
        a.setAlertId(alertId);
        a.setSeverity(severity);
        a.setCreatedAt(createdAt);
        a.setPriceAtAlert(priceAtAlert);
        return a;
    }
}
