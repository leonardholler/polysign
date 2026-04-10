package com.polysign.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LiquidityTier#classify(double, double, double)} and
 * {@link LiquidityTier#classifyRaw(String, double, double)}.
 *
 * <p>Default boundaries used throughout: Tier 1 &gt; $250k, Tier 2 ≥ $50k.
 */
class LiquidityTierTest {

    private static final double TIER1_MIN = 250_000.0;
    private static final double TIER2_MIN =  50_000.0;

    // ── classify(double) — boundary values ────────────────────────────────────

    @Test
    void volumeAboveTier1BoundaryReturnsTier1() {
        assertThat(LiquidityTier.classify(300_000.0, TIER1_MIN, TIER2_MIN)).isEqualTo(LiquidityTier.TIER_1);
    }

    @Test
    void volumeExactlyAtTier1BoundaryReturnsTier2() {
        // spec: Tier 1 is volume24h > $250k (strictly greater than)
        assertThat(LiquidityTier.classify(250_000.0, TIER1_MIN, TIER2_MIN)).isEqualTo(LiquidityTier.TIER_2);
    }

    @Test
    void volumeJustBelowTier1BoundaryReturnsTier2() {
        assertThat(LiquidityTier.classify(249_999.99, TIER1_MIN, TIER2_MIN)).isEqualTo(LiquidityTier.TIER_2);
    }

    @Test
    void volumeExactlyAtTier2BoundaryReturnsTier2() {
        // spec: Tier 2 is $50k ≤ volume24h ≤ $250k (inclusive at both ends)
        assertThat(LiquidityTier.classify(50_000.0, TIER1_MIN, TIER2_MIN)).isEqualTo(LiquidityTier.TIER_2);
    }

    @Test
    void volumeJustBelowTier2BoundaryReturnsTier3() {
        assertThat(LiquidityTier.classify(49_999.99, TIER1_MIN, TIER2_MIN)).isEqualTo(LiquidityTier.TIER_3);
    }

    @Test
    void volumeZeroReturnsTier3() {
        assertThat(LiquidityTier.classify(0.0, TIER1_MIN, TIER2_MIN)).isEqualTo(LiquidityTier.TIER_3);
    }

    @Test
    void negativeVolumeReturnsTier3() {
        assertThat(LiquidityTier.classify(-1.0, TIER1_MIN, TIER2_MIN)).isEqualTo(LiquidityTier.TIER_3);
    }

    @Test
    void nanVolumeReturnsTier3() {
        assertThat(LiquidityTier.classify(Double.NaN, TIER1_MIN, TIER2_MIN)).isEqualTo(LiquidityTier.TIER_3);
    }

    @Test
    void midRangeVolumeReturnsTier2() {
        assertThat(LiquidityTier.classify(150_000.0, TIER1_MIN, TIER2_MIN)).isEqualTo(LiquidityTier.TIER_2);
    }

    @Test
    void lowVolumeReturnsTier3() {
        assertThat(LiquidityTier.classify(10_000.0, TIER1_MIN, TIER2_MIN)).isEqualTo(LiquidityTier.TIER_3);
    }

    // ── classifyRaw(String) — null / invalid inputs ────────────────────────────

    @Test
    void nullRawVolumeReturnsTier3() {
        assertThat(LiquidityTier.classifyRaw(null, TIER1_MIN, TIER2_MIN)).isEqualTo(LiquidityTier.TIER_3);
    }

    @Test
    void blankRawVolumeReturnsTier3() {
        assertThat(LiquidityTier.classifyRaw("", TIER1_MIN, TIER2_MIN)).isEqualTo(LiquidityTier.TIER_3);
        assertThat(LiquidityTier.classifyRaw("   ", TIER1_MIN, TIER2_MIN)).isEqualTo(LiquidityTier.TIER_3);
    }

    @Test
    void unparsableRawVolumeReturnsTier3() {
        assertThat(LiquidityTier.classifyRaw("not-a-number", TIER1_MIN, TIER2_MIN)).isEqualTo(LiquidityTier.TIER_3);
        assertThat(LiquidityTier.classifyRaw("$100k", TIER1_MIN, TIER2_MIN)).isEqualTo(LiquidityTier.TIER_3);
    }

    @Test
    void validRawVolumeClassifiesCorrectly() {
        assertThat(LiquidityTier.classifyRaw("300000", TIER1_MIN, TIER2_MIN)).isEqualTo(LiquidityTier.TIER_1);
        assertThat(LiquidityTier.classifyRaw("100000.50", TIER1_MIN, TIER2_MIN)).isEqualTo(LiquidityTier.TIER_2);
        assertThat(LiquidityTier.classifyRaw("10000", TIER1_MIN, TIER2_MIN)).isEqualTo(LiquidityTier.TIER_3);
    }

    // ── label() ───────────────────────────────────────────────────────────────

    @Test
    void labelMatchesEnumName() {
        assertThat(LiquidityTier.TIER_1.label()).isEqualTo("TIER_1");
        assertThat(LiquidityTier.TIER_2.label()).isEqualTo("TIER_2");
        assertThat(LiquidityTier.TIER_3.label()).isEqualTo("TIER_3");
    }
}
