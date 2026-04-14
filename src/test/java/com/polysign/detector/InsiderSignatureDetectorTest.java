package com.polysign.detector;

import com.polysign.alert.AlertService;
import com.polysign.common.AppClock;
import com.polysign.model.Alert;
import com.polysign.model.Market;
import com.polysign.model.WalletTrade;
import com.polysign.wallet.WalletMetadata;
import com.polysign.wallet.WalletMetadataService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link InsiderSignatureDetector#evaluateTrade}.
 *
 * <p>Tests construct the detector manually with mocks to avoid a Spring context.
 * The {@code evaluateTrade} method is package-private so it is directly callable.
 */
@SuppressWarnings("unchecked")
class InsiderSignatureDetectorTest {

    private static final Instant NOW = Instant.parse("2026-04-13T12:00:00Z");

    private DynamoDbTable<Market>      mockMarketsTable;
    private DynamoDbTable<WalletTrade> mockWalletTradesTable;
    private WalletMetadataService      mockMetadataService;
    private AlertService               mockAlertService;
    private AppClock                   mockClock;
    private MarketLivenessGate         mockLivenessGate;

    private InsiderSignatureDetector detector;

    @BeforeEach
    void setup() {
        mockMarketsTable      = mock(DynamoDbTable.class);
        mockWalletTradesTable = mock(DynamoDbTable.class);
        mockMetadataService   = mock(WalletMetadataService.class);
        mockAlertService      = mock(AlertService.class);
        mockClock             = mock(AppClock.class);
        mockLivenessGate      = mock(MarketLivenessGate.class);

        when(mockClock.now()).thenReturn(NOW);
        when(mockClock.nowIso()).thenReturn(NOW.toString());
        when(mockAlertService.tryCreate(any())).thenReturn(true);

        // Default: no hedge trades found (hedge query now uses QueryEnhancedRequest after Fix B)
        DynamoDbIndex<WalletTrade> mockIndex = mock(DynamoDbIndex.class);
        when(mockWalletTradesTable.index("marketId-timestamp-index")).thenReturn(mockIndex);
        when(mockIndex.query(any(QueryEnhancedRequest.class))).thenReturn(emptySdkIterable());

        detector = new InsiderSignatureDetector(
                mockMarketsTable,
                mockWalletTradesTable,
                mockMetadataService,
                mockAlertService,
                mockClock,
                mockLivenessGate,
                new SimpleMeterRegistry());

        // Manually init lastRanAtIso (normally done by @PostConstruct)
        detector.setLastRanAtIso(NOW.minus(Duration.ofMinutes(5)).toString());
    }

    /** Wraps a List of Page objects into the SdkIterable the Enhanced Client returns. */
    @SuppressWarnings("unchecked")
    private static <T> SdkIterable<Page<T>> sdkIterable(List<Page<T>> pages) {
        return () -> (Iterator<Page<T>>) pages.iterator();
    }

    @SuppressWarnings("unchecked")
    private static <T> SdkIterable<Page<T>> emptySdkIterable() {
        return () -> List.<Page<T>>of().iterator();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Market market(String id, String volume24h, double price) {
        Market m = new Market();
        m.setMarketId(id);
        m.setQuestion("Will X happen?");
        m.setVolume24h(volume24h);
        m.setCurrentYesPrice(BigDecimal.valueOf(price));
        m.setConditionId("0x" + id);
        m.setEventSlug("test-event-" + id);
        return m;
    }

    private static WalletTrade trade(String address, String marketId,
                                     double sizeUsdc, String outcome) {
        WalletTrade t = new WalletTrade();
        t.setAddress(address);
        t.setTxHash("0xtx-" + address + "-" + sizeUsdc);
        t.setTimestamp(NOW.minus(Duration.ofSeconds(30)).toString());
        t.setMarketId(marketId);
        t.setSide("BUY");
        t.setOutcome(outcome);
        t.setSizeUsdc(BigDecimal.valueOf(sizeUsdc));
        t.setPrice(BigDecimal.valueOf(0.50));
        return t;
    }

    private static WalletMetadata burnerByAge(int ageDays, int tradeCount, double volumeUsd) {
        WalletMetadata m = new WalletMetadata();
        m.setAddress("0xburner");
        m.setFirstTradeAt(NOW.minus(Duration.ofDays(ageDays)).toString());
        m.setLifetimeTradeCount(tradeCount);
        m.setLifetimeVolumeUsd(BigDecimal.valueOf(volumeUsd));
        return m;
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void tradeSize_belowAbsoluteThreshold_noAlert() {
        // $150K market: max($1K, 0.5%×$150K=$750) = $1K absolute floor
        // Trade = $999 < $1,000 → no alert
        Market m = market("m1", "150000", 0.50);
        WalletTrade t = trade("0xwallet", "m1", 999.0, "YES");
        WalletMetadata meta = burnerByAge(5, 3, 20_000);
        when(mockMetadataService.get(any())).thenReturn(meta);

        boolean fired = detector.evaluateTrade(t, m);

        assertThat(fired).isFalse();
        verifyNoInteractions(mockAlertService);
    }

    @Test
    void tradeSize_aboveAbsoluteThreshold_withBurnerWallet_firesAlert() {
        // Market vol = $400K; max($1K, 0.5%×$400K=$2K) = $2K threshold
        // Trade = $15K > $2K; wallet age 5 days = burner
        Market m = market("m1", "400000", 0.50);
        WalletTrade t = trade("0xburner", "m1", 15_000.0, "YES");
        WalletMetadata meta = burnerByAge(5, 3, 30_000);
        when(mockMetadataService.get(any())).thenReturn(meta);

        boolean fired = detector.evaluateTrade(t, m);

        assertThat(fired).isTrue();
        verify(mockAlertService).tryCreate(any(Alert.class));
    }

    @Test
    void tradeSize_belowPctThreshold_noAlert() {
        // market vol = $300K; max($1K, 0.5%×$300K=$1.5K) = $1.5K threshold
        // trade = $1,200 < $1,500 → no alert (below pct floor)
        Market m = market("m2", "300000", 0.50);
        WalletTrade t = trade("0xwallet", "m2", 1_200.0, "YES");
        WalletMetadata meta = burnerByAge(5, 3, 20_000);
        when(mockMetadataService.get(any())).thenReturn(meta);

        boolean fired = detector.evaluateTrade(t, m);

        assertThat(fired).isFalse();
    }

    @Test
    void tradeSize_abovePctThreshold_withBurnerWallet_firesAlert() {
        // market vol = $300K; max($1K, 0.5%×$300K=$1.5K) = $1.5K threshold
        // trade = $11K > $1.5K and wallet is a fresh burner
        Market m = market("m3", "300000", 0.50);
        WalletTrade t = trade("0xburner2", "m3", 11_000.0, "YES");
        WalletMetadata meta = burnerByAge(5, 3, 25_000);
        when(mockMetadataService.get(any())).thenReturn(meta);

        boolean fired = detector.evaluateTrade(t, m);

        assertThat(fired).isTrue();
        verify(mockAlertService).tryCreate(any(Alert.class));
    }

    @Test
    void burnerFilter_oldWallet_highTradeCount_lowVolumePct_noAlert() {
        // age=30d (> 7d), trades=50 (> 5), trade is 1% of vol (< 70%) → none of the three burner criteria
        // $400K market: max($1K, 0.5%×$400K=$2K) = $2K; $15K passes size gate
        Market m = market("m4", "400000", 0.50);
        WalletTrade t = trade("0xold", "m4", 15_000.0, "YES");
        // lifetimeVolumeUsd = $1.5M, trade = 1% of that (below 70%)
        WalletMetadata meta = burnerByAge(30, 50, 1_500_000.0);
        when(mockMetadataService.get(any())).thenReturn(meta);

        boolean fired = detector.evaluateTrade(t, m);

        assertThat(fired).isFalse();
    }

    @Test
    void burnerFilter_freshWallet_fires() {
        // wallet age = 5 days → isBurnerByAge = true
        // $400K market: max($1K, $2K) = $2K; $15K passes size gate
        Market m = market("m5", "400000", 0.50);
        WalletTrade t = trade("0xfresh", "m5", 15_000.0, "YES");
        WalletMetadata meta = burnerByAge(5, 100, 1_500_000.0);
        when(mockMetadataService.get(any())).thenReturn(meta);

        boolean fired = detector.evaluateTrade(t, m);

        assertThat(fired).isTrue();
    }

    @Test
    void burnerFilter_lowTradeCount_fires() {
        // 4 lifetime trades ≤ 5 → isBurnerByCount = true
        // $400K market: max($1K, $2K) = $2K; $15K passes size gate
        Market m = market("m6", "400000", 0.50);
        WalletTrade t = trade("0xlowcount", "m6", 15_000.0, "YES");
        WalletMetadata meta = burnerByAge(60, 4, 1_500_000.0);
        when(mockMetadataService.get(any())).thenReturn(meta);

        boolean fired = detector.evaluateTrade(t, m);

        assertThat(fired).isTrue();
    }

    @Test
    void burnerFilter_highVolumePct_fires() {
        // trade = $12K; wallet vol = $15K → 80% ≥ 70% → isBurnerByVolume = true
        // $400K market: max($1K, $2K) = $2K; $12K passes size gate
        Market m = market("m7", "400000", 0.50);
        WalletTrade t = trade("0xhighvol", "m7", 12_000.0, "YES");
        WalletMetadata meta = burnerByAge(60, 100, 15_000.0);
        when(mockMetadataService.get(any())).thenReturn(meta);

        boolean fired = detector.evaluateTrade(t, m);

        assertThat(fired).isTrue();
    }

    @Test
    void directionFilter_hedgedTrade_noAlert() {
        // Wallet also bought NO — hedge detected, skip.
        // After Fix B the hedge query uses QueryEnhancedRequest; the mock simulates DynamoDB
        // returning only this wallet's BUY trades (address+side already filtered server-side).
        Market m = market("m8", "400000", 0.50);
        WalletTrade buyYes = trade("0xhedger", "m8", 15_000.0, "YES");
        WalletMetadata meta = burnerByAge(5, 3, 30_000);
        when(mockMetadataService.get(any())).thenReturn(meta);

        // DynamoDB returns the opposing BUY NO trade from the same wallet
        WalletTrade buyNo = trade("0xhedger", "m8", 14_000.0, "NO");
        Page<WalletTrade> hedgePage = mock(Page.class);
        when(hedgePage.items()).thenReturn(List.of(buyNo));
        DynamoDbIndex<WalletTrade> mockIndex = mock(DynamoDbIndex.class);
        when(mockWalletTradesTable.index("marketId-timestamp-index")).thenReturn(mockIndex);
        when(mockIndex.query(any(QueryEnhancedRequest.class)))
                .thenReturn(sdkIterable(List.of(hedgePage)));

        boolean fired = detector.evaluateTrade(buyYes, m);

        assertThat(fired).isFalse();
    }

    @Test
    void tradeSize_1200_on_200kMarket_firesAlert() {
        // $200K market: max($1K, 0.5%×$200K=$1K) = $1K threshold
        // Trade = $1,200 > $1,000; wallet is 3 days old → isBurnerByAge → should fire
        Market m = market("m10", "200000", 0.50);
        WalletTrade t = trade("0xnewwallet", "m10", 1_200.0, "YES");
        WalletMetadata meta = burnerByAge(3, 2, 2_000);
        when(mockMetadataService.get(any())).thenReturn(meta);

        boolean fired = detector.evaluateTrade(t, m);

        assertThat(fired).isTrue();
        verify(mockAlertService).tryCreate(any(Alert.class));
    }

    @Test
    void tradeSize_1200_on_500kMarket_noAlert() {
        // $500K market: max($1K, 0.5%×$500K=$2.5K) = $2.5K threshold
        // Trade = $1,200 < $2,500 → below sliding floor → no alert even with fresh wallet
        Market m = market("m11", "500000", 0.50);
        WalletTrade t = trade("0xnewwallet2", "m11", 1_200.0, "YES");
        WalletMetadata meta = burnerByAge(3, 2, 2_000);
        when(mockMetadataService.get(any())).thenReturn(meta);

        boolean fired = detector.evaluateTrade(t, m);

        assertThat(fired).isFalse();
        verifyNoInteractions(mockAlertService);
    }

    @Test
    void cooldown_sameWalletSameMarket_24h_noSecondAlert() {
        // First alert fires; second call returns false (deduplicated by AlertService)
        // $400K market: max($1K, $2K) = $2K; $15K passes size gate
        Market m = market("m9", "400000", 0.50);
        WalletTrade t = trade("0xcooldown", "m9", 15_000.0, "YES");
        WalletMetadata meta = burnerByAge(5, 3, 30_000);
        when(mockMetadataService.get(any())).thenReturn(meta);

        when(mockAlertService.tryCreate(any())).thenReturn(true).thenReturn(false);

        boolean first  = detector.evaluateTrade(t, m);
        boolean second = detector.evaluateTrade(t, m);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        verify(mockAlertService, times(2)).tryCreate(any(Alert.class));
    }

    // ── Fix A+B: hedge-window tests ───────────────────────────────────────────

    @Test
    void hedgeWithin2hWindow_30min_blocksAlert() {
        // Wallet made a contradictory BUY NO 30 minutes ago — within the new 2h window.
        // DynamoDB (mocked) returns that trade; hedge is detected; alert must NOT fire.
        Market m = market("m13", "400000", 0.50);
        WalletTrade buyYes = trade("0xhedger2", "m13", 15_000.0, "YES");
        WalletMetadata meta = burnerByAge(5, 3, 30_000);
        when(mockMetadataService.get(any())).thenReturn(meta);

        // Simulate DynamoDB returning the hedge trade (BUY NO, 30 min ago, same wallet).
        // After Fix B the DynamoDB filter already restricts to address+side=BUY, so only
        // the opposite-outcome trade is in the result.
        WalletTrade hedgeNo = trade("0xhedger2", "m13", 12_000.0, "NO");
        hedgeNo.setTimestamp(NOW.minus(Duration.ofMinutes(30)).toString());
        Page<WalletTrade> hedgePage = mock(Page.class);
        when(hedgePage.items()).thenReturn(List.of(hedgeNo));
        DynamoDbIndex<WalletTrade> mockIndex = mock(DynamoDbIndex.class);
        when(mockWalletTradesTable.index("marketId-timestamp-index")).thenReturn(mockIndex);
        when(mockIndex.query(any(QueryEnhancedRequest.class)))
                .thenReturn(sdkIterable(List.of(hedgePage)));

        boolean fired = detector.evaluateTrade(buyYes, m);

        assertThat(fired).isFalse();
    }

    @Test
    void hedgeOutside2hWindow_4h_firesAlert() {
        // Wallet made a contradictory BUY NO 4 hours ago — outside the new 2h window.
        // The GSI range query with a 2h lookback would exclude that trade. We simulate
        // this by having the hedge mock return empty (as DynamoDB would with a 2h SK filter).
        // The alert must fire because no in-window hedge is detected.
        Market m = market("m14", "400000", 0.50);
        WalletTrade buyYes = trade("0xreversal", "m14", 15_000.0, "YES");
        WalletMetadata meta = burnerByAge(5, 3, 30_000);
        when(mockMetadataService.get(any())).thenReturn(meta);
        // Hedge mock is already empty from @BeforeEach — no in-window hedge trade returned.

        boolean fired = detector.evaluateTrade(buyYes, m);

        assertThat(fired).isTrue();
        verify(mockAlertService).tryCreate(any(Alert.class));
    }

    // ── Fix 2: Trade recency filter tests ────────────────────────────────────

    @Test
    void staleTrade_olderThan24h_noAlert() {
        // Trade timestamp is 25 hours ago — exceeds TRADE_MAX_AGE (24h) → must be rejected.
        Market m = market("m15", "400000", 0.50);
        WalletTrade t = trade("0xstale", "m15", 15_000.0, "YES");
        t.setTimestamp(NOW.minus(Duration.ofHours(25)).toString());
        WalletMetadata meta = burnerByAge(3, 2, 20_000);
        when(mockMetadataService.get(any())).thenReturn(meta);

        boolean fired = detector.evaluateTrade(t, m);

        assertThat(fired).isFalse();
        verifyNoInteractions(mockAlertService);
        // Metadata service must NOT be called (recency check is before size gate)
        verifyNoInteractions(mockMetadataService);
    }

    @Test
    void staleTrade_at23h59m_firesAlert() {
        // Trade timestamp is 23h59m ago — just inside the 24h TRADE_MAX_AGE window → must fire.
        Market m = market("m16", "400000", 0.50);
        WalletTrade t = trade("0xedge", "m16", 15_000.0, "YES");
        t.setTimestamp(NOW.minus(Duration.ofHours(23).plusMinutes(59)).toString());
        WalletMetadata meta = burnerByAge(3, 2, 20_000);
        when(mockMetadataService.get(any())).thenReturn(meta);

        boolean fired = detector.evaluateTrade(t, m);

        assertThat(fired).isTrue();
        verify(mockAlertService).tryCreate(any(Alert.class));
    }

    @Test
    void regression_prodBadAlert_36dWallet_8trades_22pctVol_noAlert() {
        // Regression for the real prod alert that incorrectly fired:
        //   $43.4K trade, 36-day-old wallet, 8 lifetime trades, 22.4% of wallet volume.
        // With tightened thresholds none of the three burner conditions passes:
        //   isBurnerByAge:    36d > 7d  → false
        //   isBurnerByCount:  8   > 5   → false
        //   isBurnerByVolume: 22.4% < 70% → false
        Market m = market("prod-bad", "400000", 0.55);
        WalletTrade t = trade("0xprod-bad", "prod-bad", 43_400.0, "YES");
        // lifetime vol ≈ $193,750 → 43_400 / 193_750 ≈ 22.4%
        WalletMetadata meta = burnerByAge(36, 8, 193_750.0);
        when(mockMetadataService.get(any())).thenReturn(meta);

        boolean fired = detector.evaluateTrade(t, m);

        assertThat(fired).isFalse();
        verifyNoInteractions(mockAlertService);
    }

    // ── Fix C: MAX_TRADES_PER_RUN cap test ───────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void maxTradesPerRun_capsAt500AndLogsWarn() {
        // 6 qualifying markets × 101 trades each = 606 total qualifying trades.
        // The cap (MAX_TRADES_PER_RUN=500) must fire mid-way through the 5th market.
        // Assert: exactly 500 metadata lookups, "insider_detector_capped" warn logged.

        Logger detectorLogger = (Logger) org.slf4j.LoggerFactory.getLogger(InsiderSignatureDetector.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        detectorLogger.addAppender(appender);

        try {
            WalletMetadata burner = burnerByAge(5, 3, 30_000);
            when(mockMetadataService.get(any())).thenReturn(burner);
            when(mockAlertService.tryCreate(any())).thenReturn(false);

            // 101 BUY YES trades at $15K — all pass size gate on a $400K market
            List<WalletTrade> trades101 = IntStream.range(0, 101)
                    .mapToObj(i -> {
                        WalletTrade t = new WalletTrade();
                        t.setAddress("0xburner" + i);
                        t.setTxHash("0xtx-cap-" + i);
                        t.setTimestamp(NOW.minus(Duration.ofSeconds(30)).toString());
                        t.setMarketId("cap-market");
                        t.setSide("BUY");
                        t.setOutcome("YES");
                        t.setSizeUsdc(BigDecimal.valueOf(15_000));
                        t.setPrice(BigDecimal.valueOf(0.50));
                        return t;
                    })
                    .toList();

            // 6 qualifying markets
            List<Market> capMarkets = IntStream.range(0, 6)
                    .mapToObj(i -> market("cap-" + i, "400000", 0.50))
                    .toList();

            PageIterable<Market> mockScan = mock(PageIterable.class);
            when(mockScan.items()).thenReturn(() -> capMarkets.iterator());
            when(mockMarketsTable.scan()).thenReturn(mockScan);

            // Shared index: QueryConditional → 101 trades; QueryEnhancedRequest → empty (no hedge)
            DynamoDbIndex<WalletTrade> capIndex = mock(DynamoDbIndex.class);
            when(mockWalletTradesTable.index("marketId-timestamp-index")).thenReturn(capIndex);

            Page<WalletTrade> tradePage = mock(Page.class);
            when(tradePage.items()).thenReturn(trades101);
            SdkIterable<Page<WalletTrade>> tradePages =
                    () -> List.<Page<WalletTrade>>of(tradePage).iterator();
            when(capIndex.query(any(software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional.class)))
                    .thenReturn(tradePages);
            when(capIndex.query(any(QueryEnhancedRequest.class))).thenReturn(emptySdkIterable());

            detector.run();

            // Cap must have fired — warn log present
            boolean cappedLogged = appender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains("insider_detector_capped"));
            assertThat(cappedLogged).isTrue();

            // Exactly 500 trades evaluated (all reach metadata lookup: BUY + size gate pass)
            verify(mockMetadataService, times(500)).get(any());

        } finally {
            detectorLogger.detachAppender(appender);
        }
    }

    // ── priceAtAlert is populated at fire time ────────────────────────────────

    @Test
    void priceAtAlert_isSetToMarketCurrentYesPrice() {
        Market m = market("m1", "400000", 0.48); // currentYesPrice = 0.48
        WalletTrade t = trade("0xburner", "m1", 15_000.0, "YES");
        WalletMetadata meta = burnerByAge(5, 3, 30_000);
        when(mockMetadataService.get(any())).thenReturn(meta);

        detector.evaluateTrade(t, m);

        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(mockAlertService).tryCreate(captor.capture());
        Alert alert = captor.getValue();

        assertThat(alert.getPriceAtAlert())
                .isNotNull()
                .isEqualByComparingTo(new BigDecimal("0.48")); // market.getCurrentYesPrice()
    }
}
