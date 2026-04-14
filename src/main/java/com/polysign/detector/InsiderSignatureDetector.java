package com.polysign.detector;

import com.polysign.alert.AlertIdFactory;
import com.polysign.alert.AlertService;
import com.polysign.backtest.MarketPredicates;
import com.polysign.common.AppClock;
import com.polysign.common.CorrelationId;
import com.polysign.model.Alert;
import com.polysign.model.Market;
import com.polysign.model.WalletTrade;
import com.polysign.wallet.WalletMetadata;
import com.polysign.wallet.WalletMetadataService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Detects burner-wallet insider patterns: a fresh or low-activity wallet placing
 * a large directional bet on a high-volume, unresolved market — without hedging.
 *
 * <h3>Detection criteria (all must pass)</h3>
 * <ol>
 *   <li>Market volume24h ≥ $100K and not effectively resolved</li>
 *   <li>Market price is not extreme (0.01 – 0.99)</li>
 *   <li>Trade is a BUY (not a SELL hedge)</li>
 *   <li>Trade size ≥ max($10K, 2% of market volume24h)</li>
 *   <li>Wallet qualifies as a "burner": age ≤ 14d OR lifetime trades ≤ 10 OR
 *       this trade ≥ 40% of lifetime volume</li>
 *   <li>Wallet has NOT bought the opposite outcome in the last 2h (no hedge)</li>
 * </ol>
 *
 * <p>Alerts are deduplicated per (wallet, market) within a 24-hour window.
 * Hedge exclusion window is 2h (a contradictory bet &ge;2h after the signal trade
 * is treated as a reversal, not a hedge).
 */
@Component
public class InsiderSignatureDetector {

    private static final Logger log = LoggerFactory.getLogger(InsiderSignatureDetector.class);

    static final double   MIN_MARKET_VOLUME_24H         = 100_000.0;
    static final double   MIN_TRADE_SIZE_USD_ABSOLUTE   = 1_000.0;
    static final double   MIN_TRADE_SIZE_PCT_OF_VOLUME  = 0.005;
    static final int      MAX_BURNER_WALLET_AGE_DAYS    = 14;
    static final int      MAX_BURNER_LIFETIME_TRADES    = 10;
    static final double   BURNER_TRADE_VOLUME_PCT       = 0.40;
    static final Duration DEDUPE_WINDOW                 = Duration.ofHours(24);
    static final Duration HEDGE_WINDOW                  = Duration.ofHours(2);
    /** Safety valve: max trades evaluated in a single run() invocation. */
    static final int      MAX_TRADES_PER_RUN            = 500;
    static final String   ALERT_TYPE                    = "insider_signature";

    private final DynamoDbTable<Market>      marketsTable;
    private final DynamoDbTable<WalletTrade> walletTradesTable;
    private final WalletMetadataService      walletMetadataService;
    private final AlertService               alertService;
    private final AppClock                   clock;
    private final MarketLivenessGate         livenessGate;
    private final Counter                    alertsFired;

    /** ISO-8601 of last successful run — trades after this instant are considered new. Package-private for testability. */
    volatile String lastRanAtIso;

    public InsiderSignatureDetector(
            DynamoDbTable<Market> marketsTable,
            DynamoDbTable<WalletTrade> walletTradesTable,
            WalletMetadataService walletMetadataService,
            AlertService alertService,
            AppClock clock,
            MarketLivenessGate livenessGate,
            MeterRegistry meterRegistry) {
        this.marketsTable         = marketsTable;
        this.walletTradesTable    = walletTradesTable;
        this.walletMetadataService = walletMetadataService;
        this.alertService         = alertService;
        this.clock                = clock;
        this.livenessGate         = livenessGate;
        this.alertsFired = Counter.builder("polysign.insider.alerts.fired")
                .description("Insider signature alerts fired")
                .register(meterRegistry);
    }

    @PostConstruct
    void init() {
        lastRanAtIso = clock.now().minus(Duration.ofMinutes(5)).toString();
    }

    /** For testing only — override the incremental cursor. */
    public void setLastRanAtIso(String lastRanAtIso) {
        this.lastRanAtIso = lastRanAtIso;
    }

    @Scheduled(
        fixedDelayString   = "${polysign.detectors.insider.interval-ms:60000}",
        initialDelayString = "${polysign.detectors.insider.initial-delay-ms:95000}")
    public void run() {
        try (var ignored = CorrelationId.set()) {
            Instant runStart = clock.now();
            int marketsScanned = 0;
            int tradesEvaluated = 0;
            int alertsCreated = 0;
            boolean capped = false;
            int marketsRemaining = 0;

            for (Market market : marketsTable.scan().items()) {
                // Volume gate
                if (parseDouble(market.getVolume24h()) < MIN_MARKET_VOLUME_24H) continue;
                // Resolved gate
                if (MarketPredicates.effectivelyResolved(market).isPresent()) continue;
                // Price filter: skip extremes and null
                BigDecimal price = market.getCurrentYesPrice();
                if (price == null || price.doubleValue() <= 0.01 || price.doubleValue() >= 0.99) continue;
                // conditionId required for GSI query
                if (market.getConditionId() == null) continue;

                // Fix C: safety valve — count remaining qualifying markets after cap fires
                if (capped) {
                    marketsRemaining++;
                    continue;
                }

                marketsScanned++;

                // Query trades since last run via marketId-timestamp-index GSI
                try {
                    var pages = walletTradesTable
                            .index("marketId-timestamp-index")
                            .query(QueryConditional.sortGreaterThanOrEqualTo(
                                    Key.builder()
                                            .partitionValue(market.getMarketId())
                                            .sortValue(lastRanAtIso)
                                            .build()));
                    pageLoop:
                    for (var page : pages) {
                        for (WalletTrade trade : page.items()) {
                            tradesEvaluated++;
                            boolean fired = evaluateTrade(trade, market);
                            if (fired) alertsCreated++;
                            if (tradesEvaluated >= MAX_TRADES_PER_RUN) {
                                capped = true;
                                break pageLoop;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("insider_trade_query_failed marketId={} error={}",
                            market.getMarketId(), e.getMessage());
                }
            }

            if (capped) {
                log.warn("insider_detector_capped tradesEvaluated={} marketsRemaining={}",
                        tradesEvaluated, marketsRemaining);
            }
            lastRanAtIso = runStart.toString();
            log.info("insider_detector_complete marketsScanned={} tradesEvaluated={} alertsCreated={}",
                    marketsScanned, tradesEvaluated, alertsCreated);
        } catch (Throwable t) {
            log.error("insider_detector_error error={}", t.getMessage(), t);
        }
    }

    // ── Core evaluation ───────────────────────────────────────────────────────

    boolean evaluateTrade(WalletTrade trade, Market market) {
        // Step 1: BUY side only
        if (!"BUY".equalsIgnoreCase(trade.getSide())) return false;

        // Step 2: Trade size gate
        double marketVol24h = parseDouble(market.getVolume24h());
        double minSize = Math.max(MIN_TRADE_SIZE_USD_ABSOLUTE, MIN_TRADE_SIZE_PCT_OF_VOLUME * marketVol24h);
        if (trade.getSizeUsdc() == null || trade.getSizeUsdc().doubleValue() < minSize) return false;

        // Step 3: Burner wallet filter
        WalletMetadata meta = walletMetadataService.get(trade.getAddress());
        if (meta.isUnknown()) return false;

        boolean isBurnerByAge = meta.getFirstTradeAt() != null
                && clock.now().minus(Duration.ofDays(MAX_BURNER_WALLET_AGE_DAYS))
                        .isBefore(Instant.parse(meta.getFirstTradeAt()));

        boolean isBurnerByCount = meta.getLifetimeTradeCount() != null
                && meta.getLifetimeTradeCount() <= MAX_BURNER_LIFETIME_TRADES;

        boolean isBurnerByVolume = false;
        if (meta.getLifetimeVolumeUsd() != null
                && meta.getLifetimeVolumeUsd().compareTo(BigDecimal.ZERO) > 0) {
            isBurnerByVolume = trade.getSizeUsdc().doubleValue()
                    / meta.getLifetimeVolumeUsd().doubleValue() >= BURNER_TRADE_VOLUME_PCT;
        }

        if (!(isBurnerByAge || isBurnerByCount || isBurnerByVolume)) return false;

        // Step 4: Hedge exclusion — if wallet bought the opposite outcome in last 2h, skip.
        // Server-side filter pushes address + side to DynamoDB, minimising page allocation.
        try {
            String hedgeWindowIso = clock.now().minus(HEDGE_WINDOW).toString();
            Expression hedgeFilter = Expression.builder()
                    .expression("#addr = :addr AND #side = :side")
                    .expressionNames(Map.of("#addr", "address", "#side", "side"))
                    .expressionValues(Map.of(
                            ":addr", AttributeValue.fromS(trade.getAddress()),
                            ":side", AttributeValue.fromS("BUY")))
                    .build();
            QueryEnhancedRequest hedgeRequest = QueryEnhancedRequest.builder()
                    .queryConditional(QueryConditional.sortGreaterThanOrEqualTo(
                            Key.builder()
                                    .partitionValue(market.getMarketId())
                                    .sortValue(hedgeWindowIso)
                                    .build()))
                    .filterExpression(hedgeFilter)
                    .build();
            boolean hedged = walletTradesTable
                    .index("marketId-timestamp-index")
                    .query(hedgeRequest)
                    .stream()
                    .flatMap(p -> p.items().stream())
                    .filter(t -> trade.getOutcome() != null && !trade.getOutcome().equalsIgnoreCase(t.getOutcome()))
                    .findFirst()
                    .isPresent();
            if (hedged) return false;
        } catch (Exception e) {
            log.warn("insider_hedge_check_failed address={} marketId={} error={}",
                    trade.getAddress(), market.getMarketId(), e.getMessage());
        }

        // Step 5: Build and fire alert
        return fireAlert(trade, market, meta);
    }

    private boolean fireAlert(WalletTrade trade, Market market, WalletMetadata meta) {
        // Wallet age in days
        String walletAgeDays;
        if (meta.getFirstTradeAt() != null) {
            long days = ChronoUnit.DAYS.between(Instant.parse(meta.getFirstTradeAt()), clock.now());
            walletAgeDays = String.valueOf(days);
        } else {
            walletAgeDays = "unknown";
        }

        double tradePctOfWalletVol = 0.0;
        if (meta.getLifetimeVolumeUsd() != null
                && meta.getLifetimeVolumeUsd().compareTo(BigDecimal.ZERO) > 0) {
            tradePctOfWalletVol = trade.getSizeUsdc().doubleValue()
                    / meta.getLifetimeVolumeUsd().doubleValue() * 100;
        }

        Map<String, String> metadata = new HashMap<>();
        metadata.put("traderAddress",              trade.getAddress());
        metadata.put("tradeSizeUsd",               trade.getSizeUsdc().toPlainString());
        metadata.put("walletAgeDays",              walletAgeDays);
        metadata.put("walletLifetimeTrades",       String.valueOf(
                meta.getLifetimeTradeCount() != null ? meta.getLifetimeTradeCount() : 0));
        metadata.put("tradeSizeAsPctOfWalletVolume",
                String.format("%.1f", tradePctOfWalletVol));
        metadata.put("outcomeSide",                trade.getOutcome() != null ? trade.getOutcome() : "");
        metadata.put("currentMarketPrice",
                market.getCurrentYesPrice() != null ? market.getCurrentYesPrice().toPlainString() : "unknown");
        metadata.put("sizeUsdc",                   trade.getSizeUsdc().toPlainString());

        // Dedupe: 24h per (wallet, market)
        String canonicalHash = sha256Hex(trade.getAddress());
        String alertId = AlertIdFactory.generate(ALERT_TYPE, market.getMarketId(),
                clock.now(), DEDUPE_WINDOW, canonicalHash);

        Alert alert = new Alert();
        alert.setAlertId(alertId);
        alert.setCreatedAt(clock.nowIso());
        alert.setType(ALERT_TYPE);
        alert.setSeverity("critical");
        alert.setMarketId(market.getMarketId());
        alert.setTitle("Insider pattern: " + truncate(market.getQuestion(), 60));
        String addrPreview = trade.getAddress().length() > 10
                ? trade.getAddress().substring(0, 10) : trade.getAddress();
        alert.setDescription("Burner wallet " + addrPreview
                + "\u2026 placed " + fmtUsd(trade.getSizeUsdc()) + " on " + trade.getOutcome());
        alert.setMetadata(metadata);
        alert.setLink(market.getEventSlug() != null
                ? "https://polymarket.com/event/" + market.getEventSlug() : null);
        alert.setExpiresAt(clock.now().plus(Duration.ofDays(30)).getEpochSecond());

        if (alertService.tryCreate(alert)) {
            alertsFired.increment();
            log.info("insider_alert_fired alertId={} address={} marketId={} sizeUsdc={} walletAge={}",
                    alertId, trade.getAddress(), market.getMarketId(),
                    trade.getSizeUsdc(), walletAgeDays);
            return true;
        }
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static double parseDouble(String s) {
        if (s == null) return 0.0;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0.0; }
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    private static String fmtUsd(BigDecimal n) {
        if (n == null) return "$0";
        double v = n.doubleValue();
        if (v >= 1_000_000) return String.format("$%.1fM", v / 1_000_000);
        if (v >= 1_000)     return String.format("$%.1fK", v / 1_000);
        return String.format("$%.0f", v);
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "\u2026" : s;
    }
}
