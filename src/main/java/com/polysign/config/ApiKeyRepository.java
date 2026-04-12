package com.polysign.config;

import com.polysign.common.ApiKeyHasher;
import com.polysign.common.AppClock;
import com.polysign.model.ApiKey;
import com.polysign.model.Tier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.util.Optional;

/**
 * Data-access layer for the {@code api_keys} DynamoDB table.
 *
 * <p>Raw API keys are NEVER stored. Only the SHA-256 hex digest (apiKeyHash) is
 * persisted as the partition key. Callers receive the raw key exactly once
 * via {@link CreatedApiKey}; it is unrecoverable after that.
 *
 * <p>Logs use keyPrefix (first 8 chars of the raw key) for identification, never
 * the raw key or its hash.
 */
@Repository
public class ApiKeyRepository {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyRepository.class);

    private static final int RATE_LIMIT_FREE = 60;
    private static final int RATE_LIMIT_PRO  = 600;

    private final DynamoDbTable<ApiKey> apiKeysTable;
    private final AppClock              appClock;

    public ApiKeyRepository(DynamoDbTable<ApiKey> apiKeysTable, AppClock appClock) {
        this.apiKeysTable = apiKeysTable;
        this.appClock     = appClock;
    }

    /**
     * Looks up a key by hashing {@code rawKey} and querying DynamoDB.
     * Returns empty if the key does not exist.
     */
    public Optional<ApiKey> findByRawKey(String rawKey) {
        String hash = ApiKeyHasher.hash(rawKey);
        ApiKey item = apiKeysTable.getItem(Key.builder().partitionValue(hash).build());
        return Optional.ofNullable(item);
    }

    /** Persists or overwrites an {@link ApiKey} record. */
    public void save(ApiKey apiKey) {
        apiKeysTable.putItem(apiKey);
    }

    /**
     * Sets {@code active=false} on the record identified by {@code apiKeyHash}.
     * No-op if the key does not exist.
     */
    public void deactivate(String apiKeyHash) {
        ApiKey existing = apiKeysTable.getItem(Key.builder().partitionValue(apiKeyHash).build());
        if (existing == null) {
            log.warn("api_key_deactivate_not_found prefix=unknown hash_prefix={}",
                    apiKeyHash.substring(0, Math.min(8, apiKeyHash.length())));
            return;
        }
        existing.setActive(false);
        apiKeysTable.putItem(existing);
        log.info("api_key_deactivated prefix={}", existing.getKeyPrefix());
    }

    /**
     * Generates a new raw key, hashes it, persists the {@link ApiKey}, and returns
     * a {@link CreatedApiKey} containing the raw key for one-time display to the client.
     *
     * <p>Rate limit is set by tier: FREE={@value RATE_LIMIT_FREE}/min, PRO={@value RATE_LIMIT_PRO}/min.
     */
    public CreatedApiKey createNew(String clientName, Tier tier) {
        String rawKey    = ApiKeyHasher.generateRawKey();
        String hash      = ApiKeyHasher.hash(rawKey);
        String keyPrefix = rawKey.substring(0, Math.min(8, rawKey.length()));
        String createdAt = appClock.nowIso();

        ApiKey apiKey = new ApiKey();
        apiKey.setApiKeyHash(hash);
        apiKey.setClientName(clientName);
        apiKey.setTier(tier.name());
        apiKey.setRateLimit(tier == Tier.PRO ? RATE_LIMIT_PRO : RATE_LIMIT_FREE);
        apiKey.setCreatedAt(createdAt);
        apiKey.setActive(true);
        apiKey.setKeyPrefix(keyPrefix);

        apiKeysTable.putItem(apiKey);
        log.info("api_key_created client={} tier={} prefix={}", clientName, tier.name(), keyPrefix);

        return new CreatedApiKey(rawKey, hash, clientName, tier, keyPrefix, createdAt);
    }
}
