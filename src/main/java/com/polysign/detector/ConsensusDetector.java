package com.polysign.detector;

import com.polysign.alert.AlertIdFactory;
import com.polysign.alert.AlertService;
import com.polysign.common.AppClock;
import com.polysign.model.Alert;
import com.polysign.model.WalletTrade;
import com.polysign.model.WatchedWallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Fires a {@code critical} consensus alert when ≥ {@code consensusMinWallets} distinct
 * watched wallets trade the same market in the same direction within the consensus window.
 *
 * <p>Called synchronously by {@link com.polysign.poller.WalletPoller} after each
 * successful trade write. It queries the {@code marketId-timestamp-index} GSI to find
 * all watched-wallet trades on the same market in the last {@code consensusWindow}
 * minutes, then groups by direction (BUY or SELL) and counts distinct addresses.
 *
 * <p>Alert deduplication: the AlertIdFactory 30-minute window ensures one consensus
 * alert per (market, window) — repeated calls within the same bucket produce the
 * same alertId and the {@code attribute_not_exists} DynamoDB condition rejects the dup.
 */
@Component
public class ConsensusDetector {

    private static final Logger log = LoggerFactory.getLogger(ConsensusDetector.class);
    private static final String ALERT_TYPE = "consensus";

    private final DynamoDbTable<WalletTrade>   walletTradesTable;
    private final DynamoDbTable<WatchedWallet> watchedWalletsTable;
    private final AlertService alertService;
    private final AppClock clock;
    private final Duration consensusWindow;
    private final int consensusMinWallets;

    @Autowired
    public ConsensusDetector(
            DynamoDbTable<WalletTrade>   walletTradesTable,
            DynamoDbTable<WatchedWallet> watchedWalletsTable,
            AlertService alertService,
            AppClock clock,
            @Value("${polysign.detectors.wallet.consensus-window-minutes:30}") int consensusWindowMinutes,
            @Value("${polysign.detectors.wallet.consensus-min-wallets:3}") int consensusMinWallets) {
        this.walletTradesTable   = walletTradesTable;
        this.watchedWalletsTable = watchedWalletsTable;
        this.alertService        = alertService;
        this.clock               = clock;
        this.consensusWindow     = Duration.ofMinutes(consensusWindowMinutes);
        this.consensusMinWallets = consensusMinWallets;
    }

    // Constructor for unit-test injection (Duration instead of int minutes, no watchedWalletsTable).
    ConsensusDetector(DynamoDbTable<WalletTrade> walletTradesTable,
                      AlertService alertService,
                      AppClock clock,
                      Duration consensusWindow,
                      int consensusMinWallets) {
        this.walletTradesTable   = walletTradesTable;
        this.watchedWalletsTable = null; // not needed in unit tests — alias falls back to address prefix
        this.alertService        = alertService;
        this.clock               = clock;
        this.consensusWindow     = consensusWindow;
        this.consensusMinWallets = consensusMinWallets;
    }

    /**
     * Check for a consensus signal on the market of the triggering trade.
     *
     * @param triggeringTrade the trade that was just written to DynamoDB
     * @param slug            the Polymarket event slug from the Data API response;
     *                        used to construct the deep-link URL in the alert
     */
    public void checkConsensus(WalletTrade triggeringTrade, String slug) {
        String marketId = triggeringTrade.getMarketId();

        Instant now         = clock.now();
        Instant windowStart = now.minus(consensusWindow);

        List<WalletTrade> candidates = queryRecentTrades(marketId, windowStart, now);

        // Belt-and-suspenders time filter. The GSI query should handle this,
        // but if the stub in tests returns out-of-window trades, this guards correctly.
        List<WalletTrade> inWindow = candidates.stream()
                .filter(t -> t.getTimestamp() != null
                        && !Instant.parse(t.getTimestamp()).isBefore(windowStart))
                .toList();

        // Group distinct wallet addresses per direction.
        Map<String, Set<String>> directionToAddresses = new HashMap<>();
        for (WalletTrade t : inWindow) {
            if (t.getSide() == null || t.getAddress() == null) continue;
            directionToAddresses
                    .computeIfAbsent(t.getSide(), k -> new HashSet<>())
                    .add(t.getAddress().toLowerCase());
        }

        // Fire on the first direction that meets the threshold.
        for (Map.Entry<String, Set<String>> entry : directionToAddresses.entrySet()) {
            String direction = entry.getKey();
            Set<String> distinctWallets = entry.getValue();

            if (distinctWallets.size() >= consensusMinWallets) {
                // Collect the trades for this direction to build per-wallet detail later.
                List<WalletTrade> consensusTrades = inWindow.stream()
                        .filter(t -> direction.equals(t.getSide())
                                && t.getAddress() != null
                                && distinctWallets.contains(t.getAddress().toLowerCase()))
                        .toList();

                // Find the most common outcome (YES/NO) among the agreeing wallets.
                Map<String, Long> outcomeCounts = consensusTrades.stream()
                        .filter(t -> t.getOutcome() != null)
                        .collect(Collectors.groupingBy(WalletTrade::getOutcome, Collectors.counting()));
                String outcome = outcomeCounts.isEmpty() ? "?"
                        : outcomeCounts.entrySet().stream()
                                .max(Map.Entry.comparingByValue())
                                .map(Map.Entry::getKey)
                                .orElse("?");

                fireConsensusAlert(marketId, direction, outcome, distinctWallets, slug, now,
                        triggeringTrade.getPrice(), consensusTrades);
                return; // one alert per call — both directions cannot be checked simultaneously
            }
        }
    }

    private void fireConsensusAlert(String marketId, String direction, String outcome,
                                    Set<String> distinctWallets, String slug, Instant now,
                                    BigDecimal currentPrice, List<WalletTrade> consensusTrades) {
        String alertId    = AlertIdFactory.generate(ALERT_TYPE, marketId, now, consensusWindow);
        Instant createdAt = AlertIdFactory.bucketedInstant(now, consensusWindow);

        // Per-wallet size sums (one wallet may have multiple trades within the window).
        Map<String, BigDecimal> sizeByAddress = new HashMap<>();
        for (WalletTrade t : consensusTrades) {
            if (t.getAddress() == null) continue;
            String addr = t.getAddress().toLowerCase();
            BigDecimal sz = t.getSizeUsdc() != null ? t.getSizeUsdc() : BigDecimal.ZERO;
            sizeByAddress.merge(addr, sz, BigDecimal::add);
        }
        BigDecimal totalSizeUsdc = sizeByAddress.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Sort by size descending: "alias:BUY:2400.00|alias2:BUY:1100.00"
        String walletDetails = sizeByAddress.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .map(e -> lookupAlias(e.getKey()) + ":" + direction + ":"
                        + e.getValue().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
                .collect(Collectors.joining("|"));

        // Compute implied market sentiment from direction + outcome.
        // For YES/NO binary markets: BUY YES / SELL NO = bullish; BUY NO / SELL YES = bearish.
        // For named-outcome markets (e.g. "ARIZONA DIAMONDBACKS"): BUY = bullish, SELL = bearish.
        String sentiment;
        boolean isYesNo = "YES".equalsIgnoreCase(outcome) || "NO".equalsIgnoreCase(outcome);
        if (isYesNo) {
            boolean bullish = ("BUY".equalsIgnoreCase(direction) && "YES".equalsIgnoreCase(outcome))
                           || ("SELL".equalsIgnoreCase(direction) && "NO".equalsIgnoreCase(outcome));
            sentiment = bullish ? "bullish" : "bearish";
        } else {
            // Named outcome or unknown — BUY = bullish, SELL = bearish
            sentiment = "BUY".equalsIgnoreCase(direction) ? "bullish" : "bearish";
        }
        String verb = "BUY".equalsIgnoreCase(direction) ? "buying" : "selling";

        String priceStr = currentPrice != null
                ? String.format("%.1f\u00a2", currentPrice.doubleValue() * 100.0)
                : "?";

        Map<String, String> metadata = new HashMap<>();
        metadata.put("direction",          direction);
        metadata.put("outcome",            outcome);
        metadata.put("consensusDirection", direction + " " + outcome.toUpperCase());
        metadata.put("consensusOutcome",   sentiment);
        metadata.put("walletCount",        String.valueOf(distinctWallets.size()));
        metadata.put("wallets",            String.join(",", distinctWallets));
        metadata.put("walletDetails",      walletDetails);
        metadata.put("totalSizeUsdc",      totalSizeUsdc.toPlainString());
        metadata.put("currentPrice",       currentPrice != null ? currentPrice.toPlainString() : "?");
        metadata.put("detectedAt",         now.toString());

        Alert alert = new Alert();
        alert.setAlertId(alertId);
        alert.setCreatedAt(createdAt.toString());
        alert.setType(ALERT_TYPE);
        alert.setSeverity("critical");
        alert.setMarketId(marketId);
        alert.setTitle(String.format("%d wallets %s %s (%s) — market at %s",
                distinctWallets.size(), verb, outcome.toUpperCase(), sentiment, priceStr));
        alert.setDescription(String.format(
                "%d distinct watched wallets %s %s within the last %d minutes",
                distinctWallets.size(), verb, outcome.toUpperCase(), consensusWindow.toMinutes()));
        // Data API trade slug is already event-level (no outcome-ID suffix).
        alert.setLink(slug != null ? "https://polymarket.com/event/" + slug : null);
        alert.setMetadata(metadata);

        boolean created = alertService.tryCreate(alert);
        if (created) {
            log.info("consensus_alert_fired marketId={} direction={} outcome={} sentiment={} walletCount={}",
                    marketId, direction, outcome, sentiment, distinctWallets.size());
        }
    }

    /** Returns the wallet alias from watchedWalletsTable, or an 8-char address prefix as fallback. */
    private String lookupAlias(String address) {
        if (watchedWalletsTable != null && address != null) {
            try {
                WatchedWallet w = watchedWalletsTable.getItem(
                        Key.builder().partitionValue(address).build());
                if (w != null && w.getAlias() != null && !w.getAlias().isBlank()) {
                    return w.getAlias();
                }
            } catch (Exception e) {
                log.debug("alias_lookup_failed address={}", address);
            }
        }
        return address != null ? address.substring(0, Math.min(8, address.length())) : "?";
    }

    /**
     * Queries the {@code marketId-timestamp-index} GSI for trades within [windowStart, now].
     * Package-private so unit tests can override without a real DynamoDB connection.
     */
    List<WalletTrade> queryRecentTrades(String marketId, Instant windowStart, Instant now) {
        DynamoDbIndex<WalletTrade> gsi = walletTradesTable.index("marketId-timestamp-index");

        QueryConditional qc = QueryConditional.sortBetween(
                Key.builder().partitionValue(marketId).sortValue(windowStart.toString()).build(),
                Key.builder().partitionValue(marketId).sortValue(now.toString()).build());

        return gsi.query(r -> r.queryConditional(qc).scanIndexForward(true))
                .stream()
                .flatMap(page -> page.items().stream())
                .collect(Collectors.toList());
    }

}
