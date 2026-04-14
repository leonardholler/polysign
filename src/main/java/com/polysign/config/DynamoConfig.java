package com.polysign.config;

import com.polysign.model.*;
import com.polysign.model.AlertOutcome;
import com.polysign.wallet.WalletMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

/**
 * Typed {@link DynamoDbTable} beans for each model class.
 *
 * <p>Table names default to the canonical names from the spec but can be
 * overridden via {@code application.yml} (useful for multi-env deployments).
 * Every downstream repository/service class injects the relevant table bean
 * rather than calling {@code client.table()} directly.
 */
@Configuration
public class DynamoConfig {

    @Value("${polysign.dynamodb.tables.markets:markets}")
    private String marketsTable;

    @Value("${polysign.dynamodb.tables.price-snapshots:price_snapshots}")
    private String priceSnapshotsTable;

    @Value("${polysign.dynamodb.tables.watched-wallets:watched_wallets}")
    private String watchedWalletsTable;

    @Value("${polysign.dynamodb.tables.wallet-trades:wallet_trades}")
    private String walletTradesTable;

    @Value("${polysign.dynamodb.tables.alerts:alerts}")
    private String alertsTable;

    @Value("${polysign.dynamodb.tables.alert-outcomes:alert_outcomes}")
    private String alertOutcomesTable;

    @Value("${polysign.dynamodb.tables.api-keys:api_keys}")
    private String apiKeysTable;

    @Value("${polysign.dynamodb.tables.wallet-metadata:wallet_metadata}")
    private String walletMetadataTable;

    @Bean
    public DynamoDbTable<Market> marketsTable(DynamoDbEnhancedClient client) {
        return client.table(marketsTable, TableSchema.fromBean(Market.class));
    }

    @Bean
    public DynamoDbTable<PriceSnapshot> priceSnapshotsTable(DynamoDbEnhancedClient client) {
        return client.table(priceSnapshotsTable, TableSchema.fromBean(PriceSnapshot.class));
    }

    @Bean
    public DynamoDbTable<WatchedWallet> watchedWalletsTable(DynamoDbEnhancedClient client) {
        return client.table(watchedWalletsTable, TableSchema.fromBean(WatchedWallet.class));
    }

    @Bean
    public DynamoDbTable<WalletTrade> walletTradesTable(DynamoDbEnhancedClient client) {
        return client.table(walletTradesTable, TableSchema.fromBean(WalletTrade.class));
    }

    @Bean
    public DynamoDbTable<Alert> alertsTable(DynamoDbEnhancedClient client) {
        return client.table(alertsTable, TableSchema.fromBean(Alert.class));
    }

    @Bean
    public DynamoDbTable<AlertOutcome> alertOutcomesTable(DynamoDbEnhancedClient client) {
        return client.table(alertOutcomesTable, TableSchema.fromBean(AlertOutcome.class));
    }

    @Bean
    public DynamoDbTable<ApiKey> apiKeysTable(DynamoDbEnhancedClient client) {
        return client.table(apiKeysTable, TableSchema.fromBean(ApiKey.class));
    }

    @Bean
    public DynamoDbTable<WalletMetadata> walletMetadataTable(DynamoDbEnhancedClient client) {
        return client.table(walletMetadataTable, TableSchema.fromBean(WalletMetadata.class));
    }
}
