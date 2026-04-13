package com.polysign.backtest;

import com.polysign.model.Market;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MarketPredicates#effectivelyResolved(Market)}.
 */
class MarketPredicatesTest {

    // ── (a) resolvedBy = null → empty ─────────────────────────────────────────

    @Test
    void resolvedBy_null_returnsEmpty() {
        Market m = market(null, List.of("0.9995", "0.0005"));
        assertThat(MarketPredicates.effectivelyResolved(m)).isEmpty();
    }

    // ── (a-variant) resolvedBy = blank → empty ────────────────────────────────

    @Test
    void resolvedBy_blank_returnsEmpty() {
        Market m = market("   ", List.of("0.9995", "0.0005"));
        assertThat(MarketPredicates.effectivelyResolved(m)).isEmpty();
    }

    // ── (b) resolvedBy set but price in mid-range → empty ────────────────────

    @Test
    void resolvedBy_set_midPrice_returnsEmpty() {
        Market m = market("0x65070BE91477460D8A7AeEb94ef92fe056C2f2A7", List.of("0.50", "0.50"));
        assertThat(MarketPredicates.effectivelyResolved(m)).isEmpty();
    }

    @Test
    void resolvedBy_set_priceJustBelowThreshold_returnsEmpty() {
        // 0.98 is below DECISIVE_HIGH (0.99) so not decisive
        Market m = market("0xOracle", List.of("0.98", "0.02"));
        assertThat(MarketPredicates.effectivelyResolved(m)).isEmpty();
    }

    @Test
    void resolvedBy_set_priceJustAboveLowThreshold_returnsEmpty() {
        // 0.02 is above DECISIVE_LOW (0.01) so not decisive
        Market m = market("0xOracle", List.of("0.02", "0.98"));
        assertThat(MarketPredicates.effectivelyResolved(m)).isEmpty();
    }

    // ── (c) both conditions met, YES winning → Optional(1.0) ─────────────────

    @Test
    void resolvedBy_set_yesDecisive_returnsOnePointZero() {
        // Gamma real-world example: outcomePrices = ["0.9995","0.0005"]
        Market m = market("0x65070BE91477460D8A7AeEb94ef92fe056C2f2A7", List.of("0.9995", "0.0005"));
        Optional<BigDecimal> result = MarketPredicates.effectivelyResolved(m);
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo("1");
    }

    @Test
    void resolvedBy_set_yesExactlyAtThreshold_returnsOnePointZero() {
        // Exactly 0.99 — boundary inclusive
        Market m = market("0xOracle", List.of("0.99", "0.01"));
        Optional<BigDecimal> result = MarketPredicates.effectivelyResolved(m);
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo("1");
    }

    // ── (d) both conditions met, NO winning → Optional(0.0) ──────────────────

    @Test
    void resolvedBy_set_noDecisive_returnsZeroPointZero() {
        // YES price = 0.0005 → NO won
        Market m = market("0x65070BE91477460D8A7AeEb94ef92fe056C2f2A7", List.of("0.0005", "0.9995"));
        Optional<BigDecimal> result = MarketPredicates.effectivelyResolved(m);
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo("0");
    }

    @Test
    void resolvedBy_set_yesExactlyAtLowThreshold_returnsZeroPointZero() {
        // Exactly 0.01 — boundary inclusive
        Market m = market("0xOracle", List.of("0.01", "0.99"));
        Optional<BigDecimal> result = MarketPredicates.effectivelyResolved(m);
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo("0");
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    void outcomePrices_null_returnsEmpty() {
        Market m = market("0xOracle", null);
        assertThat(MarketPredicates.effectivelyResolved(m)).isEmpty();
    }

    @Test
    void outcomePrices_empty_returnsEmpty() {
        Market m = market("0xOracle", List.of());
        assertThat(MarketPredicates.effectivelyResolved(m)).isEmpty();
    }

    @Test
    void outcomePrices_unparseable_returnsEmpty() {
        Market m = market("0xOracle", List.of("not-a-number", "0.0005"));
        assertThat(MarketPredicates.effectivelyResolved(m)).isEmpty();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static Market market(String resolvedBy, List<String> outcomePrices) {
        Market m = new Market();
        m.setResolvedBy(resolvedBy);
        m.setOutcomePrices(outcomePrices);
        return m;
    }
}
