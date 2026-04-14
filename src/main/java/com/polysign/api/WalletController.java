package com.polysign.api;

import com.polysign.model.WalletTrade;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.util.Comparator;
import java.util.List;

/**
 * REST endpoints for wallet trade data.
 *
 * <ul>
 *   <li>GET /api/wallets/{address}/trades?limit=50</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/wallets")
public class WalletController {

    private final DynamoDbTable<WalletTrade> walletTradesTable;

    public WalletController(DynamoDbTable<WalletTrade> walletTradesTable) {
        this.walletTradesTable = walletTradesTable;
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

        QueryConditional qc = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(address.toLowerCase()).build());

        return walletTradesTable.query(qc).items().stream()
                .sorted(Comparator.comparing(WalletTrade::getTimestamp,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .toList();
    }
}
