package com.polysign.common;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Utility for generating and hashing API keys.
 *
 * <p>Raw keys are NEVER persisted. Only the SHA-256 hex digest is stored.
 * The raw key is shown to the client once at creation and is unrecoverable after.
 */
public final class ApiKeyHasher {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int RAW_KEY_BYTES = 32;

    private ApiKeyHasher() {}

    /**
     * Returns the SHA-256 hex digest of {@code rawKey}.
     * This is what gets stored in DynamoDB as the PK.
     */
    public static String hash(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(rawKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return bytesToHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the Java spec
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Generates a new raw API key: 32 random bytes, base64url-encoded (no padding), prefixed {@code psk_}.
     * Example: {@code psk_3q2-7wEBAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcY}
     */
    public static String generateRawKey() {
        byte[] bytes = new byte[RAW_KEY_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return "psk_" + encoded;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
