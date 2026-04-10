package com.polysign.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polysign.model.WatchedWallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Fallback wallet bootstrap — seeds the {@code watched_wallets} DynamoDB table from
 * {@code watched_wallets.json} on the classpath. Runs once at startup after
 * {@link WalletDiscovery} (Order=2) has attempted the Polymarket leaderboard API.
 *
 * <p>Uses {@code attribute_not_exists(address)} so that existing rows (seeded by
 * WalletDiscovery or with a real {@code lastSyncedAt} set by WalletPoller) are
 * never overwritten. Running this bootstrap repeatedly or restarting the app is safe.
 */
@Component
@Order(3)
public class WalletBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WalletBootstrap.class);

    private static final Expression NOT_EXISTS = Expression.builder()
            .expression("attribute_not_exists(address)")
            .build();

    private final DynamoDbTable<WatchedWallet> watchedWalletsTable;
    private final ObjectMapper mapper;

    public WalletBootstrap(DynamoDbTable<WatchedWallet> watchedWalletsTable, ObjectMapper mapper) {
        this.watchedWalletsTable = watchedWalletsTable;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("WalletBootstrap starting — seeding watched_wallets from classpath");
        try (InputStream is = getClass().getResourceAsStream("/watched_wallets.json")) {
            if (is == null) {
                log.warn("wallet_bootstrap_skipped reason=watched_wallets.json_not_found");
                return;
            }

            List<Map<String, Object>> seeds = mapper.readValue(is, new TypeReference<>() {});
            int seeded  = 0;
            int skipped = 0;

            for (Map<String, Object> seed : seeds) {
                String address = ((String) seed.getOrDefault("address", "")).toLowerCase();
                if (address.isBlank()) continue;

                WatchedWallet wallet = new WatchedWallet();
                wallet.setAddress(address);
                wallet.setAlias((String) seed.get("alias"));
                wallet.setCategory((String) seed.get("category"));
                wallet.setNotes((String) seed.get("notes"));
                // lastSyncedAt intentionally left null — WalletPoller sets it on first sync.

                try {
                    watchedWalletsTable.putItem(PutItemEnhancedRequest.builder(WatchedWallet.class)
                            .item(wallet)
                            .conditionExpression(NOT_EXISTS)
                            .build());
                    log.debug("wallet_seeded address={} alias={}", address, wallet.getAlias());
                    seeded++;
                } catch (ConditionalCheckFailedException e) {
                    // Already exists (has lastSyncedAt set by WalletPoller) — do not overwrite.
                    log.debug("wallet_seed_skipped address={} reason=already_exists", address);
                    skipped++;
                }
            }

            log.info("wallet_bootstrap_complete seeded={} skipped={}", seeded, skipped);

        } catch (Exception e) {
            log.error("wallet_bootstrap_failed error={}", e.getMessage(), e);
            // Non-fatal: app continues. WalletPoller will have no wallets to track
            // until the table is seeded manually, but it will not crash.
        }
    }
}
