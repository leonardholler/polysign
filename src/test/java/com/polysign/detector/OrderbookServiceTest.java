package com.polysign.detector;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link OrderbookService} spread and depth computation.
 *
 * <p>Tests the pure computation methods with synthetic book data.
 * Network-level failure and timeout behavior is tested through the
 * detector tests (which mock the OrderbookService).
 */
class OrderbookServiceTest {

    // ── Synthetic book ──────────────────────────────────────────────────────
    //
    // Bids (sorted DESC by price):
    //   0.50 × 1000    (best bid, within 1% of mid)
    //   0.49 × 500     (within 1% of 0.505: lower = 0.49995 → 0.49 < 0.49995, OUTSIDE)
    //   0.45 × 2000    (outside 1% of mid)
    //
    // Asks (sorted ASC by price):
    //   0.51 × 800     (best ask, within 1% of mid)
    //   0.52 × 600     (within 1% of 0.505: upper = 0.51005 → 0.52 > 0.51005, OUTSIDE)
    //   0.55 × 1500    (outside 1% of mid)
    //
    // midpoint = (0.50 + 0.51) / 2 = 0.505
    // spreadBps = (0.51 - 0.50) / 0.505 * 10000 = 198.02 bps
    // depthAtMid:
    //   bid 0.50 × 1000 = 500.0  (0.50 >= 0.505*0.99=0.49995 → YES)
    //   bid 0.49 × 500  = 245.0  (0.49 >= 0.49995 → NO)
    //   ask 0.51 × 800  = 408.0  (0.51 <= 0.505*1.01=0.51005 → YES)
    //   ask 0.52 × 600  = 312.0  (0.52 <= 0.51005 → NO)
    //   total = 500.0 + 408.0 = 908.0

    private static final List<OrderbookService.Level> BIDS = List.of(
            new OrderbookService.Level(0.50, 1000),
            new OrderbookService.Level(0.49, 500),
            new OrderbookService.Level(0.45, 2000)
    );

    private static final List<OrderbookService.Level> ASKS = List.of(
            new OrderbookService.Level(0.51, 800),
            new OrderbookService.Level(0.52, 600),
            new OrderbookService.Level(0.55, 1500)
    );

    private static final double MIDPOINT = 0.505;

    @Test
    void computeSpreadBpsWithKnownBook() {
        double spread = OrderbookService.computeSpreadBps(0.50, 0.51, MIDPOINT);
        // (0.51 - 0.50) / 0.505 * 10000 = 198.0198...
        assertThat(spread).isCloseTo(198.02, within(0.01));
    }

    @Test
    void computeDepthAtMidWithKnownBook() {
        double depth = OrderbookService.computeDepthAtMid(BIDS, ASKS, MIDPOINT);
        // Only bid @ 0.50 and ask @ 0.51 are within 1% of midpoint 0.505
        // 1000*0.50 + 800*0.51 = 500 + 408 = 908
        assertThat(depth).isCloseTo(908.0, within(0.01));
    }

    @Test
    void emptyBidsProducesZeroDepth() {
        double depth = OrderbookService.computeDepthAtMid(List.of(), ASKS, MIDPOINT);
        // Only ask side contributes: 800*0.51 = 408
        assertThat(depth).isCloseTo(408.0, within(0.01));
    }

    @Test
    void tightBookHasSmallSpread() {
        // bid=0.500, ask=0.501, mid=0.5005
        // spread = 0.001 / 0.5005 * 10000 = 19.98 bps
        double spread = OrderbookService.computeSpreadBps(0.500, 0.501, 0.5005);
        assertThat(spread).isCloseTo(19.98, within(0.01));
    }
}
