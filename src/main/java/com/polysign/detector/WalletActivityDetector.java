package com.polysign.detector;

import com.polysign.alert.AlertIdFactory;
import com.polysign.alert.AlertService;
import com.polysign.common.AppClock;
import com.polysign.model.Alert;
import com.polysign.model.WalletTrade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fires an {@code info} alert of type {@code wallet_activity} when an individual
 * watched-wallet trade exceeds {@code polysign.detectors.wallet.min-trade-usdc}
 * (default $5,000).
 *
 * <p>Called synchronously by {@link com.polysign.poller.WalletPoller} after each
 * successful trade write. No scheduler — driven by the polling cycle.
 *
 * <p>Alert deduplication: a 24-hour window keyed on txHash ensures the same on-chain
 * trade never fires more than one alert per day, even if the poller re-fetches it
 * across multiple cycles. txHash is the canonical natural-key for a trade.
 */
@Component
public class WalletActivityDetector {

    private static final Logger log = LoggerFactory.getLogger(WalletActivityDetector.class);
    private static final String ALERT_TYPE = "wallet_activity";

    private final AlertService alertService;
    private final AppClock clock;
    private final double minTradeUsdc;

    /**
     * Dedup key strategy: composite of wallet address and txHash, bucketed into a 24-hour window.
     * The same on-chain transaction always produces the same alert ID within the same day,
     * so re-fetching a trade across multiple poll cycles never fires more than one alert.
     */
    static final String DEDUP_KEY_STRATEGY = "address|txHash, 24-hour bucket window";

    // ── Diagnostic counters (accumulated since process start) ─────────────────
    private final AtomicLong totalAboveThreshold  = new AtomicLong();
    private final AtomicLong totalAlertsFired     = new AtomicLong();
    private final AtomicLong totalBelowThreshold  = new AtomicLong();
    private final AtomicLong totalDuplicates      = new AtomicLong();
    private volatile Instant lastTradeAt;

    // ── Per-hour time-windowed tracking ──────────────────────────────────────
    private final ConcurrentLinkedDeque<Instant> rawAboveThresholdEvents  = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Instant> uniqueAboveThresholdEvents = new ConcurrentLinkedDeque<>();
    /** Counts the total number of dedup decisions logged at INFO to cap noise. */
    private final AtomicInteger dupInfoLogCount = new AtomicInteger(0);

    public record WhaleDetectorDiagnostics(
            double minTradeUsdcThreshold,
            long totalTradesAboveThreshold,
            long totalAlertsFired,
            long totalTradesBelowThreshold,
            long totalDuplicates,
            String lastTradeAt,
            String dedupKeyStrategy,
            long rawTradesAboveThresholdLastHour,
            long uniqueTradesAboveThresholdLastHour) {}

    public WhaleDetectorDiagnostics getDiagnostics(Instant since) {
        // Prune stale events from the per-hour deques before counting
        while (!rawAboveThresholdEvents.isEmpty() && rawAboveThresholdEvents.peekFirst().isBefore(since)) {
            rawAboveThresholdEvents.pollFirst();
        }
        while (!uniqueAboveThresholdEvents.isEmpty() && uniqueAboveThresholdEvents.peekFirst().isBefore(since)) {
            uniqueAboveThresholdEvents.pollFirst();
        }
        return new WhaleDetectorDiagnostics(
                minTradeUsdc,
                totalAboveThreshold.get(),
                totalAlertsFired.get(),
                totalBelowThreshold.get(),
                totalDuplicates.get(),
                lastTradeAt != null ? lastTradeAt.toString() : null,
                DEDUP_KEY_STRATEGY,
                rawAboveThresholdEvents.size(),
                uniqueAboveThresholdEvents.size());
    }

    public WalletActivityDetector(
            AlertService alertService,
            AppClock clock,
            @Value("${polysign.detectors.wallet.min-trade-usdc:5000}") double minTradeUsdc) {
        this.alertService  = alertService;
        this.clock         = clock;
        this.minTradeUsdc  = minTradeUsdc;
    }

    /**
     * Check a single trade and fire an alert if it is large enough.
     *
     * @param trade the trade that was just written to DynamoDB
     * @param alias the wallet alias from the watched_wallets config (for readable alerts)
     * @param slug  the Polymarket event slug from the Data API response
     */
    public void checkTrade(WalletTrade trade, String alias, String slug) {
        if (trade.getSizeUsdc() == null) return;

        double sizeUsdc = trade.getSizeUsdc().doubleValue();
        lastTradeAt = clock.now();
        if (sizeUsdc < minTradeUsdc) {
            totalBelowThreshold.incrementAndGet();
            return;
        }
        totalAboveThreshold.incrementAndGet();

        Instant now = clock.now();
        rawAboveThresholdEvents.addLast(now);

        String walletLabel = alias != null ? alias : trade.getAddress();
        String direction   = trade.getSide() != null ? trade.getSide().toLowerCase() : "?";
        String outcome     = trade.getOutcome() != null ? trade.getOutcome() : "?";

        // txHash is the canonical idempotency key: one on-chain trade = one alert.
        // A 24-hour bucketing window ensures the same txHash processed in multiple
        // poll cycles on the same day always maps to the same (alertId, createdAt)
        // composite key, so the DynamoDB attribute_not_exists condition deduplicates it.
        String txHash     = trade.getTxHash() != null ? trade.getTxHash() : "";
        String dedupeKey  = trade.getAddress() + "|" + txHash;
        Duration dedupeWindow = Duration.ofMinutes(1440); // 24 hours
        String alertId    = AlertIdFactory.generate(
                ALERT_TYPE, trade.getMarketId(), now, dedupeWindow, dedupeKey);
        Instant createdAt = AlertIdFactory.bucketedInstant(now, dedupeWindow);

        Map<String, String> metadata = new HashMap<>();
        metadata.put("address",    trade.getAddress());
        metadata.put("alias",      alias != null ? alias : "unknown");
        metadata.put("side",       trade.getSide());
        metadata.put("outcome",    outcome);
        metadata.put("sizeUsdc",   String.format("%.2f", sizeUsdc));
        metadata.put("price",      trade.getPrice() != null
                                   ? trade.getPrice().toPlainString() : "?");
        metadata.put("entryPrice", trade.getPrice() != null
                                   ? trade.getPrice().toPlainString() : null);
        metadata.put("txHash",     trade.getTxHash());
        metadata.put("detectedAt", now.toString());

        Alert alert = new Alert();
        alert.setAlertId(alertId);
        alert.setCreatedAt(createdAt.toString());
        alert.setType(ALERT_TYPE);
        alert.setSeverity("info");
        alert.setMarketId(trade.getMarketId());
        alert.setTitle(String.format("Whale trade: %s %s $%.0f on %s",
                walletLabel, direction, sizeUsdc, outcome));
        alert.setDescription(String.format(
                "%s %s %s $%.2f USDC at price %.4f — %s",
                walletLabel, direction, outcome, sizeUsdc,
                trade.getPrice() != null ? trade.getPrice().doubleValue() : 0.0,
                trade.getMarketQuestion() != null ? trade.getMarketQuestion() : trade.getMarketId()));
        String link;
        if (slug != null) {
            // Data API trade slug is already event-level (no outcome-ID suffix).
            link = "https://polymarket.com/event/" + slug;
        } else if (trade.getMarketQuestion() != null) {
            link = "https://polymarket.com/search?query="
                    + URLEncoder.encode(trade.getMarketQuestion(), StandardCharsets.UTF_8);
        } else {
            link = null;
        }
        alert.setLink(link);
        alert.setMetadata(metadata);

        boolean created = alertService.tryCreate(alert);
        if (created) {
            totalAlertsFired.incrementAndGet();
            uniqueAboveThresholdEvents.addLast(now);
            log.info("event=wallet_activity_alert_fired address={} alias={} sizeUsdc={} marketId={}",
                    trade.getAddress(), alias, String.format("%.2f", sizeUsdc), trade.getMarketId());
        } else {
            totalDuplicates.incrementAndGet();
            int logNum = dupInfoLogCount.incrementAndGet();
            if (logNum <= 10) {
                log.info("event=wallet_trade_deduplicated#{} dedupeKey={} address={} txHash={} marketId={}",
                        logNum, dedupeKey, trade.getAddress(), txHash, trade.getMarketId());
            } else {
                log.debug("event=wallet_trade_skipped_duplicate txHash={} address={}",
                        txHash, trade.getAddress());
            }
        }
    }

}
