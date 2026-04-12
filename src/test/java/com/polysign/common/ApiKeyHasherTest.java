package com.polysign.common;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyHasherTest {

    // ── hash() ────────────────────────────────────────────────────────────────

    @Test
    void hashProducesLowercaseHex64Chars() {
        String digest = ApiKeyHasher.hash("psk_somekeyvalue");
        assertThat(digest).hasSize(64);
        assertThat(digest).matches("[0-9a-f]{64}");
    }

    @Test
    void hashIsDeterministic() {
        String key = "psk_abc123";
        assertThat(ApiKeyHasher.hash(key)).isEqualTo(ApiKeyHasher.hash(key));
    }

    @Test
    void differentInputsProduceDifferentHashes() {
        assertThat(ApiKeyHasher.hash("psk_key1")).isNotEqualTo(ApiKeyHasher.hash("psk_key2"));
    }

    @Test
    void hashMatchesKnownSha256Vector() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        // Verified against sha256sum and openssl dgst -sha256.
        String result = ApiKeyHasher.hash("abc");
        assertThat(result).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    // ── generateRawKey() ──────────────────────────────────────────────────────

    @Test
    void generatedKeyHasPskPrefix() {
        assertThat(ApiKeyHasher.generateRawKey()).startsWith("psk_");
    }

    @Test
    void generatedKeyIsAtLeast40Chars() {
        // psk_ (4) + base64url of 32 bytes (43 chars without padding) = 47 minimum
        assertThat(ApiKeyHasher.generateRawKey()).hasSizeGreaterThanOrEqualTo(40);
    }

    @Test
    void generatedKeysAreUnique() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            keys.add(ApiKeyHasher.generateRawKey());
        }
        assertThat(keys).hasSize(1000);
    }

    @Test
    void keyPrefixIsFirst8Chars() {
        String raw = ApiKeyHasher.generateRawKey();
        // keyPrefix convention: first 8 chars of the raw key
        String prefix = raw.substring(0, 8);
        assertThat(prefix).startsWith("psk_");
    }
}
