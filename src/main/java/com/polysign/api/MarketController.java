package com.polysign.api;

import com.polysign.common.AppClock;
import com.polysign.model.Market;
import com.polysign.model.PriceSnapshot;
import com.polysign.model.WalletTrade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.stream.StreamSupport;

/**
 * REST endpoints for market data.
 *
 * <p>Query patterns:
 * <ul>
 *   <li>GET /api/markets?category=&watchedOnly=&limit=</li>
 *   <li>GET /api/markets/{marketId}</li>
 *   <li>GET /api/markets/{marketId}/price-history?windowMinutes=60</li>
 *   <li>GET /api/markets/{marketId}/whale-trades</li>
 *   <li>POST /api/markets/{marketId}/watch</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/markets")
public class MarketController {

    private static final Logger log = LoggerFactory.getLogger(MarketController.class);

    private final DynamoDbTable<Market>        marketsTable;
    private final DynamoDbTable<PriceSnapshot> snapshotsTable;
    private final DynamoDbTable<WalletTrade>   walletTradesTable;
    private final AppClock                     clock;

    public MarketController(
            DynamoDbTable<Market> marketsTable,
            DynamoDbTable<PriceSnapshot> priceSnapshotsTable,
            DynamoDbTable<WalletTrade> walletTradesTable,
            AppClock clock) {
        this.marketsTable      = marketsTable;
        this.snapshotsTable    = priceSnapshotsTable;
        this.walletTradesTable = walletTradesTable;
        this.clock             = clock;
    }

    // ── GET /api/markets ─────────────────────────────────────────────────────

    @GetMapping
    public List<Market> listMarkets(
            @RequestParam(required = false) String category,
            @RequestParam(required = false, defaultValue = "false") boolean watchedOnly,
            @RequestParam(required = false, defaultValue = "100") int limit) {

        if (limit < 1 || limit > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and 500");
        }

        if (category != null && !category.isBlank()) {
            // GSI query — returns markets in updatedAt order for a category
            DynamoDbIndex<Market> gsi = marketsTable.index("category-updatedAt-index");
            QueryConditional qc = QueryConditional.keyEqualTo(
                    Key.builder().partitionValue(category).build());
            return gsi.query(r -> r.queryConditional(qc).scanIndexForward(false))
                    .stream()
                    .flatMap(page -> page.items().stream())
                    .filter(m -> !watchedOnly || Boolean.TRUE.equals(m.getIsWatched()))
                    .limit(limit)
                    .toList();
        }

        return marketsTable.scan().items().stream()
                .filter(m -> !watchedOnly || Boolean.TRUE.equals(m.getIsWatched()))
                .limit(limit)
                .toList();
    }

    // ── GET /api/markets/{marketId} ──────────────────────────────────────────

    @GetMapping("/{marketId}")
    public ResponseEntity<Market> getMarket(@PathVariable String marketId) {
        Market market = marketsTable.getItem(Key.builder().partitionValue(marketId).build());
        if (market == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Market not found: " + marketId);
        }
        return ResponseEntity.ok(market);
    }

    // ── GET /api/markets/{marketId}/price-history ────────────────────────────

    public record PriceHistoryPoint(String timestamp, BigDecimal midpoint) {}

    @GetMapping("/{marketId}/price-history")
    public List<PriceHistoryPoint> getPriceHistory(
            @PathVariable String marketId,
            @RequestParam(required = false, defaultValue = "60") int windowMinutes) {

        if (windowMinutes < 1 || windowMinutes > 1440) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "windowMinutes must be between 1 and 1440");
        }

        String cutoff = clock.now().minus(Duration.ofMinutes(windowMinutes)).toString();
        QueryConditional qc = QueryConditional.sortGreaterThanOrEqualTo(
                Key.builder().partitionValue(marketId).sortValue(cutoff).build());

        return snapshotsTable.query(r -> r.queryConditional(qc).scanIndexForward(true))
                .items()
                .stream()
                .map(s -> new PriceHistoryPoint(s.getTimestamp(), s.getMidpoint()))
                .toList();
    }

    // ── GET /api/markets/{marketId}/whale-trades ─────────────────────────────

    @GetMapping("/{marketId}/whale-trades")
    public List<WalletTrade> getWhaleTrades(@PathVariable String marketId) {
        DynamoDbIndex<WalletTrade> gsi = walletTradesTable.index("marketId-timestamp-index");
        String cutoff = clock.now().minus(Duration.ofHours(24)).toString();
        QueryConditional qc = QueryConditional.sortGreaterThanOrEqualTo(
                Key.builder().partitionValue(marketId).sortValue(cutoff).build());
        return gsi.query(r -> r.queryConditional(qc).scanIndexForward(false))
                .stream()
                .flatMap(page -> page.items().stream())
                .toList();
    }

    // ── POST /api/markets/{marketId}/watch ───────────────────────────────────

    @PostMapping("/{marketId}/watch")
    public ResponseEntity<Void> watchMarket(@PathVariable String marketId) {
        Market market = marketsTable.getItem(Key.builder().partitionValue(marketId).build());
        if (market == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Market not found: " + marketId);
        }
        market.setIsWatched(true);
        marketsTable.updateItem(market);
        log.info("market_watched marketId={}", marketId);
        return ResponseEntity.ok().build();
    }
}
