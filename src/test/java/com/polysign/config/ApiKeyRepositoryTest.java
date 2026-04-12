package com.polysign.config;

import com.polysign.common.ApiKeyHasher;
import com.polysign.common.AppClock;
import com.polysign.model.ApiKey;
import com.polysign.model.Tier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class ApiKeyRepositoryTest {

    private DynamoDbTable<ApiKey> table;
    private ApiKeyRepository      repo;

    @BeforeEach
    void setUp() {
        table = mock(DynamoDbTable.class);
        repo  = new ApiKeyRepository(table, new AppClock());
    }

    @Test
    void findByRawKeyReturnsPresentWhenKeyExists() {
        String rawKey = "psk_testkey";
        ApiKey stored = apiKey(ApiKeyHasher.hash(rawKey), "client-a", true);
        when(table.getItem(any(Key.class))).thenReturn(stored);

        Optional<ApiKey> result = repo.findByRawKey(rawKey);

        assertThat(result).isPresent();
        assertThat(result.get().getApiKeyHash()).isEqualTo(stored.getApiKeyHash());
    }

    @Test
    void findByRawKeyReturnsEmptyWhenNotFound() {
        when(table.getItem(any(Key.class))).thenReturn(null);

        Optional<ApiKey> result = repo.findByRawKey("psk_unknown");

        assertThat(result).isEmpty();
    }

    @Test
    void saveCallsPutItem() {
        ApiKey key = apiKey("somehash", "client-b", true);

        repo.save(key);

        verify(table).putItem(key);
    }

    @Test
    void deactivateSetsActiveFalseAndSaves() {
        ApiKey existing = apiKey("hash123", "client-c", true);
        when(table.getItem(any(Key.class))).thenReturn(existing);

        repo.deactivate("hash123");

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(table).putItem(captor.capture());
        assertThat(captor.getValue().getActive()).isFalse();
    }

    @Test
    void deactivateIsNoopWhenKeyNotFound() {
        when(table.getItem(any(Key.class))).thenReturn(null);

        repo.deactivate("nonexistent-hash");

        verify(table, never()).putItem(any(ApiKey.class));
    }

    @Test
    void createNewPersistsKeyAndReturnsRawKey() {
        CreatedApiKey created = repo.createNew("demo-client", Tier.FREE);

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(table).putItem(captor.capture());
        ApiKey saved = captor.getValue();

        assertThat(created.rawKey()).startsWith("psk_");
        assertThat(created.clientName()).isEqualTo("demo-client");
        assertThat(created.tier()).isEqualTo(Tier.FREE);
        assertThat(created.keyPrefix()).isEqualTo(created.rawKey().substring(0, 8));
        assertThat(saved.getApiKeyHash()).isEqualTo(ApiKeyHasher.hash(created.rawKey()));
        assertThat(saved.getActive()).isTrue();
        assertThat(saved.getTier()).isEqualTo("FREE");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static ApiKey apiKey(String hash, String clientName, boolean active) {
        ApiKey k = new ApiKey();
        k.setApiKeyHash(hash);
        k.setClientName(clientName);
        k.setTier("FREE");
        k.setRateLimit(60);
        k.setCreatedAt("2026-04-11T00:00:00Z");
        k.setActive(active);
        k.setKeyPrefix(hash.substring(0, Math.min(8, hash.length())));
        return k;
    }
}
