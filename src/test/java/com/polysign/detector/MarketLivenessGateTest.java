package com.polysign.detector;

import com.polysign.common.AppClock;
import com.polysign.model.Market;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MarketLivenessGate}.
 *
 * <p>Fixed clock: NOW = 2026-04-13T12:00:00Z.
 * Past endDate: 2026-04-13T10:00:00Z (2 hours before NOW).
 * Future endDate: 2026-04-20T09:00:00Z (7 days after NOW).
 */
class MarketLivenessGateTest {

    private static final Instant NOW         = Instant.parse("2026-04-13T12:00:00Z");
    private static final String  PAST_DATE   = "2026-04-13T10:00:00Z";
    private static final String  FUTURE_DATE = "2026-04-20T09:00:00Z";
    private static final String  AT_NOW      = "2026-04-13T12:00:00Z"; // exactly at NOW → expired (≤ now)

    private MarketLivenessGate gate;

    @BeforeEach
    void setUp() {
        AppClock clock = new AppClock();
        clock.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        gate = new MarketLivenessGate(clock);
    }

    // ── (a) All fresh: future endDate, active=true, acceptingOrders=true → allow ──

    @Test
    void allFreshFieldsAllow() {
        Market m = market(FUTURE_DATE, true, true);
        assertThat(gate.isLive(m)).isTrue();
    }

    // ── (b) endDate in the past → block ──────────────────────────────────────────

    @Test
    void pastEndDateBlocks() {
        Market m = market(PAST_DATE, true, true);
        assertThat(gate.isLive(m)).isFalse();
    }

    @Test
    void endDateExactlyAtNowBlocks() {
        // endDate == now is NOT after now → blocked
        Market m = market(AT_NOW, true, true);
        assertThat(gate.isLive(m)).isFalse();
    }

    // ── (c) active=false → block ─────────────────────────────────────────────────

    @Test
    void activeFalseBlocks() {
        Market m = market(FUTURE_DATE, false, true);
        assertThat(gate.isLive(m)).isFalse();
    }

    // ── (d) acceptingOrders=false → block ────────────────────────────────────────

    @Test
    void acceptingOrdersFalseBlocks() {
        Market m = market(FUTURE_DATE, true, false);
        assertThat(gate.isLive(m)).isFalse();
    }

    // ── (e) All three fields null → allow (pre-Phase-13 rows must not be blocked) ──

    @Test
    void allNullFieldsAllow() {
        Market m = market(null, null, null);
        assertThat(gate.isLive(m)).isTrue();
    }

    // ── (f) Future endDate + active=true + acceptingOrders=true → allow ──────────

    @Test
    void allExplicitlyLiveAllow() {
        Market m = market(FUTURE_DATE, true, true);
        assertThat(gate.isLive(m)).isTrue();
    }

    // ── Null endDate with active=false → blocked by active flag ──────────────────

    @Test
    void nullEndDateActiveFalseBlocks() {
        Market m = market(null, false, null);
        assertThat(gate.isLive(m)).isFalse();
    }

    // ── Null endDate with acceptingOrders=false → blocked ────────────────────────

    @Test
    void nullEndDateAcceptingOrdersFalseBlocks() {
        Market m = market(null, null, false);
        assertThat(gate.isLive(m)).isFalse();
    }

    // ── Past endDate takes priority even when active/acceptingOrders are null ─────

    @Test
    void pastEndDateBlocksEvenWithNullPhase13Fields() {
        Market m = market(PAST_DATE, null, null);
        assertThat(gate.isLive(m)).isFalse();
    }

    // ── Unparseable endDate → fail-open (allow) ──────────────────────────────────

    @Test
    void unparseableEndDateAllows() {
        Market m = market("not-a-date", null, null);
        assertThat(gate.isLive(m)).isTrue();
    }

    // ── Helper ───────────────────────────────────────────────────────────────────

    private static Market market(String endDate, Boolean active, Boolean acceptingOrders) {
        Market m = new Market();
        m.setMarketId("test-market");
        m.setEndDate(endDate);
        m.setActive(active);
        m.setAcceptingOrders(acceptingOrders);
        return m;
    }
}
