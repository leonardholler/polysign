package com.polysign.config;

import com.polysign.model.ApiKey;
import com.polysign.model.Tier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;

/**
 * Creates a demo API key on first boot if {@code polysign.auth.demo-key-enabled=true}
 * and no demo key already exists. Idempotent — safe to restart.
 *
 * <p>Runs at {@code @Order(2)}, after {@link BootstrapRunner} ({@code @Order(1)})
 * has created the {@code api_keys} table.
 */
@Component
@Order(2)
public class ApiKeyBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyBootstrap.class);
    private static final String DEMO_CLIENT_NAME = "demo-client";

    private final boolean               demoKeyEnabled;
    private final ApiKeyRepository      apiKeyRepository;
    private final DynamoDbTable<ApiKey> apiKeysTable;

    public ApiKeyBootstrap(
            @Value("${polysign.auth.demo-key-enabled:true}") boolean demoKeyEnabled,
            ApiKeyRepository apiKeyRepository,
            DynamoDbTable<ApiKey> apiKeysTable) {
        this.demoKeyEnabled   = demoKeyEnabled;
        this.apiKeyRepository = apiKeyRepository;
        this.apiKeysTable     = apiKeysTable;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!demoKeyEnabled) {
            log.debug("event=demo_key_bootstrap_skipped reason=disabled");
            return;
        }
        if (demoKeyExists()) {
            log.debug("event=demo_key_bootstrap_skipped reason=already_exists client={}", DEMO_CLIENT_NAME);
            return;
        }
        CreatedApiKey created = apiKeyRepository.createNew(DEMO_CLIENT_NAME, Tier.FREE);
        log.info("event=demo_api_key_created client={} prefix={}", created.clientName(), created.keyPrefix());
        log.info("event=demo_api_key_created rawKey={} — SAVE THIS KEY — IT WILL NOT BE SHOWN AGAIN",
                created.rawKey());
    }

    /**
     * Scans for any active key with {@code clientName = "demo-client"}.
     * Table is tiny at bootstrap time — scan is safe.
     */
    private boolean demoKeyExists() {
        ScanEnhancedRequest request = ScanEnhancedRequest.builder()
                .filterExpression(Expression.builder()
                        .expression("clientName = :cn")
                        .expressionValues(Map.of(":cn", AttributeValue.fromS(DEMO_CLIENT_NAME)))
                        .build())
                .limit(1)
                .build();
        return apiKeysTable.scan(request).items().stream().findFirst().isPresent();
    }
}
