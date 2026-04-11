package com.polysign.detector;

import com.polysign.alert.AlertService;
import com.polysign.common.AppClock;
import com.polysign.model.Alert;
import com.polysign.model.WalletTrade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ConsensusDetector} — TDD, all 6 spec cases.
 *
 * <p>All tests call {@code checkConsensus()} directly with synthetic trade data
 * injected via the {@link TestableConsensusDetector} subclass. No DynamoDB, no scheduler.
 *
 * <p>Window = 30 minutes, consensusMinWallets = 3.
 */
class ConsensusDetectorTest {

    private static final Instant NOW           = Instant.parse("2026-04-09T12:00:00Z");
    private static final Duration WINDOW       = Duration.ofMinutes(30);
    private static final int      MIN_WALLETS  = 3;
    private static final double   MIN_TRADE    = 5_000.0;

    private AlertService alertService;

    @BeforeEach
    void setUp() {
        alertService = mock(AlertService.class);
        when(alertService.tryCreate(any())).thenReturn(true);
    }

    // ── 1. 2 wallets same direction → no alert ────────────────────────────────

    @Test
    void twoWalletsSameDirectionNoAlert() {
        List<WalletTrade> trades = List.of(
                trade("0xAAA", "m1", "BUY", NOW.minus(Duration.ofMinutes(10))),
                trade("0xBBB", "m1", "BUY", NOW.minus(Duration.ofMinutes(5)))
        );

        detector(trades).checkConsensus(triggeringTrade("m1"), "some-slug");

        verify(alertService, never()).tryCreate(any());
    }

    // ── 2. 3 wallets same direction within 30 min → alert ────────────────────

    @Test
    void threeWalletsSameDirectionWithinWindowFiresAlert() {
        List<WalletTrade> trades = List.of(
                trade("0xAAA", "m1", "BUY", NOW.minus(Duration.ofMinutes(25))),
                trade("0xBBB", "m1", "BUY", NOW.minus(Duration.ofMinutes(15))),
                trade("0xCCC", "m1", "BUY", NOW.minus(Duration.ofMinutes(5)))
        );

        detector(trades).checkConsensus(triggeringTrade("m1"), "some-slug");

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertService, times(1)).tryCreate(captor.capture());

        Alert alert = captor.getValue();
        assertThat(alert.getType()).isEqualTo("consensus");
        assertThat(alert.getSeverity()).isEqualTo("critical");
        assertThat(alert.getMarketId()).isEqualTo("m1");
        assertThat(alert.getMetadata()).containsKey("direction");
        assertThat(alert.getMetadata().get("direction")).isEqualTo("BUY");
        assertThat(alert.getMetadata()).containsKey("walletCount");
        assertThat(alert.getMetadata().get("walletCount")).isEqualTo("3");
    }

    // ── 3. 3 wallets mixed directions → no alert ─────────────────────────────

    @Test
    void threeWalletsMixedDirectionsNoAlert() {
        // 2 BUY, 1 SELL — neither side reaches 3
        List<WalletTrade> trades = List.of(
                trade("0xAAA", "m1", "BUY",  NOW.minus(Duration.ofMinutes(20))),
                trade("0xBBB", "m1", "BUY",  NOW.minus(Duration.ofMinutes(10))),
                trade("0xCCC", "m1", "SELL", NOW.minus(Duration.ofMinutes(5)))
        );

        detector(trades).checkConsensus(triggeringTrade("m1"), "some-slug");

        verify(alertService, never()).tryCreate(any());
    }

    // ── 4. 3 wallets same direction but spanning > 30 min → no alert ─────────

    @Test
    void threeWalletsSameDirectionOutsideWindowNoAlert() {
        // 2 trades are within 30 min, 1 is 45 min ago (outside the window)
        List<WalletTrade> trades = List.of(
                trade("0xAAA", "m1", "BUY", NOW.minus(Duration.ofMinutes(45))), // OUTSIDE window
                trade("0xBBB", "m1", "BUY", NOW.minus(Duration.ofMinutes(15))),
                trade("0xCCC", "m1", "BUY", NOW.minus(Duration.ofMinutes(5)))
        );

        // queryRecentTrades stub returns all 3; the in-method window filter must exclude the old one.
        detector(trades).checkConsensus(triggeringTrade("m1"), "some-slug");

        verify(alertService, never()).tryCreate(any());
    }

    // ── 5. 3 trades same direction but only 2 distinct addresses → no alert ──

    @Test
    void onlyTwoDistinctAddressesSameDirectionNoAlert() {
        // Same wallet (0xAAA) appears twice — only 2 distinct wallets
        List<WalletTrade> trades = List.of(
                trade("0xAAA", "m1", "BUY", NOW.minus(Duration.ofMinutes(25))),
                trade("0xAAA", "m1", "BUY", NOW.minus(Duration.ofMinutes(15))), // duplicate address
                trade("0xBBB", "m1", "BUY", NOW.minus(Duration.ofMinutes(5)))
        );

        detector(trades).checkConsensus(triggeringTrade("m1"), "some-slug");

        verify(alertService, never()).tryCreate(any());
    }

    // ── 6. 4 wallets same direction → one alert (idempotency via AlertIdFactory) ─

    @Test
    void fourWalletsSameDirectionFiresExactlyOneAlert() {
        List<WalletTrade> trades = List.of(
                trade("0xAAA", "m1", "SELL", NOW.minus(Duration.ofMinutes(28))),
                trade("0xBBB", "m1", "SELL", NOW.minus(Duration.ofMinutes(20))),
                trade("0xCCC", "m1", "SELL", NOW.minus(Duration.ofMinutes(10))),
                trade("0xDDD", "m1", "SELL", NOW.minus(Duration.ofMinutes(5)))
        );

        ConsensusDetector det = detector(trades);
        det.checkConsensus(triggeringTrade("m1"), "some-slug");

        // Direction SELL has 4 distinct wallets; should fire exactly once per call.
        // AlertIdFactory dedupe window prevents cross-cycle duplication in production.
        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alertService, times(1)).tryCreate(captor.capture());

        Alert alert = captor.getValue();
        assertThat(alert.getMetadata().get("direction")).isEqualTo("SELL");
        assertThat(Integer.parseInt(alert.getMetadata().get("walletCount"))).isGreaterThanOrEqualTo(4);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ConsensusDetector detector(List<WalletTrade> stubbedTrades) {
        AppClock clock = new AppClock();
        clock.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        return new TestableConsensusDetector(alertService, clock, stubbedTrades,
                WINDOW, MIN_WALLETS);
    }

    /** A minimal triggering trade used to provide marketId to checkConsensus. */
    private static WalletTrade triggeringTrade(String marketId) {
        return trade("0xTRIGGER", marketId, "BUY", NOW);
    }

    static WalletTrade trade(String address, String marketId, String side, Instant timestamp) {
        WalletTrade t = new WalletTrade();
        t.setAddress(address);
        t.setMarketId(marketId);
        t.setSide(side);
        t.setTimestamp(timestamp.toString()); // ISO-8601 stored in DynamoDB
        t.setTxHash("0xtx-" + address + "-" + timestamp.getEpochSecond());
        t.setSizeUsdc(BigDecimal.valueOf(10_000));
        t.setPrice(BigDecimal.valueOf(0.5));
        t.setOutcome("YES");
        return t;
    }

    /**
     * Test-only subclass that stubs out the DynamoDB GSI query.
     * The constructor takes pre-built trade lists so no DynamoDB wiring is needed.
     */
    static class TestableConsensusDetector extends ConsensusDetector {
        private final List<WalletTrade> stubbedTrades;

        TestableConsensusDetector(AlertService alertService, AppClock clock,
                                   List<WalletTrade> stubbedTrades,
                                   Duration consensusWindow, int consensusMinWallets) {
            super(null, alertService, clock, consensusWindow, consensusMinWallets, 100, 500.0);
            this.stubbedTrades = stubbedTrades;
        }

        @Override
        List<WalletTrade> queryRecentTrades(String marketId, Instant windowStart, Instant now) {
            // Return ALL stubbedTrades — ConsensusDetector's in-method window filter handles exclusion.
            return stubbedTrades;
        }
    }
}
