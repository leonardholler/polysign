package com.polysign.config;

import com.polysign.model.*;
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

    @Value("${polysign.dynamodb.tables.articles:articles}")
    private String articlesTable;

    @Value("${polysign.dynamodb.tables.market-news-matches:market_news_matches}")
    private String marketNewsMatchesTable;

    @Value("${polysign.dynamodb.tables.watched-wallets:watched_wallets}")
    private String watchedWalletsTable;

    @Value("${polysign.dynamodb.tables.wallet-trades:wallet_trades}")
    private String walletTradesTable;

    @Value("${polysign.dynamodb.tables.alerts:alerts}")
    private String alertsTable;

    @Bean
    public DynamoDbTable<Market> marketsTable(DynamoDbEnhancedClient client) {
        return client.table(marketsTable, TableSchema.fromBean(Market.class));
    }

    @Bean
    public DynamoDbTable<PriceSnapshot> priceSnapshotsTable(DynamoDbEnhancedClient client) {
        return client.table(priceSnapshotsTable, TableSchema.fromBean(PriceSnapshot.class));
    }

    @Bean
    public DynamoDbTable<Article> articlesTable(DynamoDbEnhancedClient client) {
        return client.table(articlesTable, TableSchema.fromBean(Article.class));
    }

    @Bean
    public DynamoDbTable<MarketNewsMatch> marketNewsMatchesTable(DynamoDbEnhancedClient client) {
        return client.table(marketNewsMatchesTable, TableSchema.fromBean(MarketNewsMatch.class));
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
}
