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
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Fires an {@code info} alert of type {@code wallet_activity} when an individual
 * watched-wallet trade exceeds {@code polysign.detectors.wallet.min-trade-usdc}
 * (default $5,000).
 *
 * <p>Called synchronously by {@link com.polysign.poller.WalletPoller} after each
 * successful trade write. No scheduler — driven by the polling cycle.
 *
 * <p>Alert deduplication: a 5-minute window is applied, keyed on
 * (address + marketId + direction). All fills for the same wallet/market/direction
 * within the same 5-minute bucket collapse into a single alert, preventing
 * multi-level orderbook fills from generating dozens of separate alerts for one trade.
 */
@Component
public class WalletActivityDetector {

    private static final Logger log = LoggerFactory.getLogger(WalletActivityDetector.class);
    private static final String ALERT_TYPE = "wallet_activity";

    private final AlertService alertService;
    private final AppClock clock;
    private final double minTradeUsdc;

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
        if (sizeUsdc < minTradeUsdc) return;

        Instant now = clock.now();

        String walletLabel = alias != null ? alias : trade.getAddress();
        String direction   = trade.getSide() != null ? trade.getSide().toLowerCase() : "?";
        String outcome     = trade.getOutcome() != null ? trade.getOutcome() : "?";

        // Bucket by (address + marketId + direction) within a 5-minute window.
        // All fills for the same wallet/market/direction within the window share
        // one alertId — orderbook multi-level fills collapse into a single alert.
        String dedupeKey = trade.getAddress() + "|" + trade.getMarketId() + "|" + direction;
        String alertId    = AlertIdFactory.generate(
                ALERT_TYPE, trade.getMarketId(), now, Duration.ofMinutes(5), dedupeKey);
        Instant createdAt = AlertIdFactory.bucketedInstant(now, Duration.ofMinutes(5));

        Map<String, String> metadata = new HashMap<>();
        metadata.put("address",    trade.getAddress());
        metadata.put("alias",      alias != null ? alias : "unknown");
        metadata.put("side",       trade.getSide());
        metadata.put("outcome",    outcome);
        metadata.put("sizeUsdc",   String.format("%.2f", sizeUsdc));
        metadata.put("price",      trade.getPrice() != null
                                   ? trade.getPrice().toPlainString() : "?");
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
        alert.setLink(slug != null ? "https://polymarket.com/event/" + slug : null);
        alert.setMetadata(metadata);

        boolean created = alertService.tryCreate(alert);
        if (created) {
            log.info("wallet_activity_alert_fired address={} alias={} sizeUsdc={:.2f} marketId={}",
                    trade.getAddress(), alias, sizeUsdc, trade.getMarketId());
        }
    }
}
