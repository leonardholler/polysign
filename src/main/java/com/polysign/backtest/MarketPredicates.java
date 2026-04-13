package com.polysign.backtest;

import com.polysign.model.Market;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Static predicates for market resolution detection.
 *
 * <p>Kept separate from the {@link Market} model because this is domain logic, not schema.
 */
public final class MarketPredicates {

    /** YES outcome resolved (price converges to 1.0). */
    static final BigDecimal YES_WIN = BigDecimal.ONE;

    /** NO outcome resolved (YES price converges to 0.0). */
    static final BigDecimal NO_WIN = BigDecimal.ZERO;

    /** YES price at-or-above this threshold → decisively YES-resolved. */
    static final double DECISIVE_HIGH = 0.99;

    /** YES price at-or-below this threshold → decisively NO-resolved. */
    static final double DECISIVE_LOW = 0.01;

    private MarketPredicates() {}

    /**
     * Returns the derived resolution price if this market is effectively resolved,
     * or {@link Optional#empty()} if it is not.
     *
     * <p>A market is <em>effectively resolved</em> when BOTH of the following hold:
     * <ol>
     *   <li>{@code resolvedBy} is non-null and non-blank — Polymarket has assigned a UMA oracle,
     *       meaning the resolution process has formally begun.</li>
     *   <li>The YES price ({@code outcomePrices[0]}) is decisive:
     *       <ul>
     *         <li>≥ {@value #DECISIVE_HIGH} → YES won → returns {@code 1.0}</li>
     *         <li>≤ {@value #DECISIVE_LOW}  → NO won  → returns {@code 0.0}</li>
     *         <li>anything else → not yet decisive → returns empty</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <p>Returns empty if {@code outcomePrices} is null, empty, or contains an
     * unparseable value.
     */
    public static Optional<BigDecimal> effectivelyResolved(Market market) {
        // Condition 1: UMA oracle assigned
        String resolvedBy = market.getResolvedBy();
        if (resolvedBy == null || resolvedBy.isBlank()) return Optional.empty();

        // Condition 2: decisive outcome price
        List<String> outcomePrices = market.getOutcomePrices();
        if (outcomePrices == null || outcomePrices.isEmpty()) return Optional.empty();

        double yesPrice;
        try {
            yesPrice = Double.parseDouble(outcomePrices.get(0));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        if (yesPrice >= DECISIVE_HIGH) return Optional.of(YES_WIN);  // YES resolved → 1.0
        if (yesPrice <= DECISIVE_LOW)  return Optional.of(NO_WIN);   // NO  resolved → 0.0

        return Optional.empty(); // oracle assigned but price not yet decisive
    }
}
