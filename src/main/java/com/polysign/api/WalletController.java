package com.polysign.api;

import com.polysign.model.WatchedWallet;
import com.polysign.model.WalletTrade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.util.Comparator;
import java.util.List;

/**
 * REST endpoints for wallet management.
 *
 * <ul>
 *   <li>GET /api/wallets</li>
 *   <li>GET /api/wallets/{address}/trades?limit=50</li>
 *   <li>POST /api/wallets</li>
 *   <li>DELETE /api/wallets/{address}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/wallets")
public class WalletController {

    private static final Logger log = LoggerFactory.getLogger(WalletController.class);

    private final DynamoDbTable<WatchedWallet> watchedWalletsTable;
    private final DynamoDbTable<WalletTrade>   walletTradesTable;

    public WalletController(
            DynamoDbTable<WatchedWallet> watchedWalletsTable,
            DynamoDbTable<WalletTrade> walletTradesTable) {
        this.watchedWalletsTable = watchedWalletsTable;
        this.walletTradesTable   = walletTradesTable;
    }

    // ── GET /api/wallets ──────────────────────────────────────────────────────

    @GetMapping
    public List<WatchedWallet> listWallets() {
        return watchedWalletsTable.scan().items().stream().toList();
    }

    // ── GET /api/wallets/{address}/trades ─────────────────────────────────────

    @GetMapping("/{address}/trades")
    public List<WalletTrade> getWalletTrades(
            @PathVariable String address,
            @RequestParam(required = false, defaultValue = "50") int limit) {

        if (limit < 1 || limit > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and 500");
        }

        // Table SK is txHash (not timestamp), so query by PK then sort client-side.
        QueryConditional qc = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(address.toLowerCase()).build());

        return walletTradesTable.query(qc).items().stream()
                .sorted(Comparator.comparing(WalletTrade::getTimestamp,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .toList();
    }

    // ── POST /api/wallets ─────────────────────────────────────────────────────

    public record WalletRequest(
            @NotBlank String address,
            @NotBlank String alias,
            String category,
            String notes) {}

    @PostMapping
    public ResponseEntity<Void> addWallet(@Valid @RequestBody WalletRequest req) {
        WatchedWallet w = new WatchedWallet();
        w.setAddress(req.address().toLowerCase());
        w.setAlias(req.alias());
        w.setCategory(req.category());
        w.setNotes(req.notes());
        watchedWalletsTable.putItem(w);
        log.info("wallet_added address={} alias={}", w.getAddress(), w.getAlias());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // ── DELETE /api/wallets/{address} ─────────────────────────────────────────

    @DeleteMapping("/{address}")
    public ResponseEntity<Void> deleteWallet(@PathVariable String address) {
        Key key = Key.builder().partitionValue(address.toLowerCase()).build();
        WatchedWallet existing = watchedWalletsTable.getItem(key);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Wallet not found: " + address);
        }
        watchedWalletsTable.deleteItem(key);
        log.info("wallet_deleted address={}", address.toLowerCase());
        return ResponseEntity.noContent().build();
    }
}
