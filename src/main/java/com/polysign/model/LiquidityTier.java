package com.polysign.model;

/**
 * Liquidity tier classification for Polymarket markets.
 *
 * <p>Markets are classified into three tiers based on their 24-hour volume.
 * Each tier has its own detection thresholds — higher thresholds for lower-
 * liquidity markets reduce false positives caused by spread slippage rather
 * than genuine price discovery.
 *
 * <p>Tier boundaries are configurable in {@code application.yml} under
 * {@code polysign.detectors.liquidity-tiers}. Defaults:
 * <ul>
 *   <li>Tier 1 (liquid): {@code volume24h > $250,000}</li>
 *   <li>Tier 2 (moderate): {@code $50,000 ≤ volume24h ≤ $250,000}</li>
 *   <li>Tier 3 (illiquid): {@code volume24h < $50,000}</li>
 * </ul>
 *
 * <p>Null, unparseable, or negative volume always defaults to {@link #TIER_3}
 * (most conservative — do not fire unless there is very strong evidence).
 */
public enum LiquidityTier {

    TIER_1,
    TIER_2,
    TIER_3;

    /**
     * Classify a market into a liquidity tier based on its 24-hour volume.
     *
     * <p>Boundary semantics (matching the spec):
     * <ul>
     *   <li>Tier 1: {@code volume24h > tier1MinVolume}</li>
     *   <li>Tier 2: {@code tier2MinVolume ≤ volume24h ≤ tier1MinVolume}</li>
     *   <li>Tier 3: {@code volume24h < tier2MinVolume}</li>
     * </ul>
     *
     * @param volume24h      parsed 24-hour volume in USDC; NaN or negative → {@link #TIER_3}
     * @param tier1MinVolume volume strictly above which a market qualifies as Tier 1
     * @param tier2MinVolume minimum volume (inclusive) for Tier 2
     * @return the tier; never null
     */
    public static LiquidityTier classify(double volume24h, double tier1MinVolume, double tier2MinVolume) {
        if (Double.isNaN(volume24h) || volume24h < 0) {
            return TIER_3;
        }
        if (volume24h > tier1MinVolume) {
            return TIER_1;
        }
        if (volume24h >= tier2MinVolume) {
            return TIER_2;
        }
        return TIER_3;
    }

    /**
     * Classify using a raw (possibly null) volume24h string from the Market model.
     * Unparseable or null strings are treated as zero → {@link #TIER_3}.
     */
    public static LiquidityTier classifyRaw(String volume24hRaw, double tier1MinVolume, double tier2MinVolume) {
        if (volume24hRaw == null || volume24hRaw.isBlank()) {
            return TIER_3;
        }
        try {
            return classify(Double.parseDouble(volume24hRaw), tier1MinVolume, tier2MinVolume);
        } catch (NumberFormatException e) {
            return TIER_3;
        }
    }

    /** Short label suitable for log lines and alert metadata (e.g. {@code "TIER_2"}). */
    public String label() {
        return name();
    }
}
