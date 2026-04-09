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
 * <p>Alert deduplication: {@link Duration#ZERO} is passed to {@link AlertIdFactory},
 * making the txHash the disambiguator (one-second granularity × unique txHash →
 * unique alertId per trade). Reprocessing the same trade from the Data API
 * produces the same alertId and the DynamoDB condition rejects the duplicate.
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

        // txHash as canonical payload hash — one unique alertId per on-chain trade.
        // Duration.ZERO disables bucketing: same txHash + same second = same alertId.
        String alertId    = AlertIdFactory.generate(
                ALERT_TYPE, trade.getMarketId(), now, Duration.ZERO, trade.getTxHash());
        Instant createdAt = AlertIdFactory.bucketedInstant(now, Duration.ZERO);

        String walletLabel = alias != null ? alias : trade.getAddress();
        String direction   = trade.getSide() != null ? trade.getSide().toLowerCase() : "?";
        String outcome     = trade.getOutcome() != null ? trade.getOutcome() : "?";

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
