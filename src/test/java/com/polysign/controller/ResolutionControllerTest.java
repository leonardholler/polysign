package com.polysign.controller;

import com.polysign.model.Alert;
import com.polysign.model.AlertOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ResolutionController}.
 *
 * Verifies:
 * 1. Empty list returned gracefully when alert_outcomes table has no resolution rows.
 * 2. Items sorted by evaluatedAt descending.
 * 3. Non-resolution horizon rows are excluded.
 */
@ExtendWith(MockitoExtension.class)
class ResolutionControllerTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    DynamoDbTable<AlertOutcome> alertOutcomesTable;

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    DynamoDbTable<Alert> alertsTable;

    ResolutionController controller;

    @BeforeEach
    void setUp() {
        controller = new ResolutionController(alertOutcomesTable, alertsTable);
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
