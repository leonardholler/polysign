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
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.List;

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

        // Default: no hedge trades found
        DynamoDbIndex<WalletTrade> mockIndex = mock(DynamoDbIndex.class);
        when(mockWalletTradesTable.index("marketId-timestamp-index")).thenReturn(mockIndex);
        when(mockIndex.query(any(software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional.class)))
                .thenReturn(emptySdkIterable());

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
        // $9,999 < $10,000 absolute min
        Market m = market("m1", "10000000", 0.50);
        WalletTrade t = trade("0xwallet", "m1", 9_999.0, "YES");
        WalletMetadata meta = burnerByAge(5, 3, 20_000);
        when(mockMetadataService.get(any())).thenReturn(meta);

        boolean fired = detector.evaluateTrade(t, m);

        assertThat(fired).isFalse();
        verifyNoInteractions(mockAlertService);
    }

    @Test
    void tradeSize_aboveAbsoluteThreshold_withBurnerWallet_firesAlert() {
        // Market vol = $400K; 2% = $8K; absolute min = $10K; max = $10K
        // Trade = $15K > $10K; wallet age 5 days = burner
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
        // market vol = $300K; 2% = $6K; trade = $5K < $6K
        // absolute min = $10K, pct min = $6K → max is $10K
        // $5K < $10K → no alert
        Market m = market("m2", "300000", 0.50);
        WalletTrade t = trade("0xwallet", "m2", 5_000.0, "YES");
        WalletMetadata meta = burnerByAge(5, 3, 20_000);
        when(mockMetadataService.get(any())).thenReturn(meta);

        boolean fired = detector.evaluateTrade(t, m);

        assertThat(fired).isFalse();
    }

    @Test
    void tradeSize_abovePctThreshold_withBurnerWallet_firesAlert() {
        // market vol = $300K; 2% = $6K; absolute min = $10K; max = $10K
        // trade = $11K > $10K and wallet is a fresh burner
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
        // age=30d (> 14d), trades=50 (> 10), trade is 1% of vol → none of the three burner criteria
        // Use $400K market so $15K passes the size gate (max(10K, 2%*400K=8K) = 10K < 15K)
        Market m = market("m4", "400000", 0.50);
        WalletTrade t = trade("0xold", "m4", 15_000.0, "YES");
        // lifetimeVolumeUsd = $1.5M, trade = 1% of that (below 40%)
        WalletMetadata meta = burnerByAge(30, 50, 1_500_000.0);
        when(mockMetadataService.get(any())).thenReturn(meta);

        boolean fired = detector.evaluateTrade(t, m);

        assertThat(fired).isFalse();
    }

    @Test
    void burnerFilter_freshWallet_fires() {
        // wallet age = 5 days → isBurnerByAge = true
        // Use $400K market so $15K passes the size gate
        Market m = market("m5", "400000", 0.50);
        WalletTrade t = trade("0xfresh", "m5", 15_000.0, "YES");
        WalletMetadata meta = burnerByAge(5, 100, 1_500_000.0);
        when(mockMetadataService.get(any())).thenReturn(meta);

        boolean fired = detector.evaluateTrade(t, m);

        assertThat(fired).isTrue();
    }

    @Test
    void burnerFilter_lowTradeCount_fires() {
        // 8 lifetime trades ≤ 10 → isBurnerByCount = true
        // Use $400K market so $15K passes the size gate
        Market m = market("m6", "400000", 0.50);
        WalletTrade t = trade("0xlowcount", "m6", 15_000.0, "YES");
        WalletMetadata meta = burnerByAge(60, 8, 1_500_000.0);
        when(mockMetadataService.get(any())).thenReturn(meta);

        boolean fired = detector.evaluateTrade(t, m);

        assertThat(fired).isTrue();
    }

    @Test
    void burnerFilter_highVolumePct_fires() {
        // trade = $12K; wallet vol = $20K → 60% ≥ 40% → isBurnerByVolume = true
        // Use $400K market so $12K passes the size gate (max(10K, 8K) = 10K < 12K)
        Market m = market("m7", "400000", 0.50);
        WalletTrade t = trade("0xhighvol", "m7", 12_000.0, "YES");
        WalletMetadata meta = burnerByAge(60, 100, 20_000.0);
        when(mockMetadataService.get(any())).thenReturn(meta);

        boolean fired = detector.evaluateTrade(t, m);

        assertThat(fired).isTrue();
    }

    @Test
    void directionFilter_hedgedTrade_noAlert() {
        // Wallet also bought NO in the last 24h — hedge detected, skip
        Market m = market("m8", "400000", 0.50);
        WalletTrade buyYes = trade("0xhedger", "m8", 15_000.0, "YES");
        WalletMetadata meta = burnerByAge(5, 3, 30_000);
        when(mockMetadataService.get(any())).thenReturn(meta);

        // Seed a BUY NO trade from the same wallet in the GSI result
        WalletTrade buyNo = trade("0xhedger", "m8", 14_000.0, "NO");
        Page<WalletTrade> hedgePage = mock(Page.class);
        when(hedgePage.items()).thenReturn(List.of(buyYes, buyNo));
        DynamoDbIndex<WalletTrade> mockIndex = mock(DynamoDbIndex.class);
        when(mockWalletTradesTable.index("marketId-timestamp-index")).thenReturn(mockIndex);
        when(mockIndex.query(any(software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional.class)))
                .thenReturn(sdkIterable(List.of(hedgePage)));

        boolean fired = detector.evaluateTrade(buyYes, m);

        assertThat(fired).isFalse();
    }

    @Test
    void cooldown_sameWalletSameMarket_24h_noSecondAlert() {
        // First alert fires; second call returns false (deduplicated by AlertService)
        // Use $400K market so $15K passes the size gate
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
}
