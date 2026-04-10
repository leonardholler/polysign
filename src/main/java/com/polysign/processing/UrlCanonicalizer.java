package com.polysign.processing;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeMap;

/**
 * Canonicalizes a URL before hashing it to produce a stable {@code articleId}.
 *
 * <p>Canonicalization rules (applied in order):
 * <ol>
 *   <li>Lowercase the host component</li>
 *   <li>Strip the fragment ({@code #...})</li>
 *   <li>Remove known tracking query params (UTM, fbclid, gclid, mc_*)</li>
 *   <li>Strip trailing slashes from the path</li>
 *   <li>Preserve all other query params — they may be semantically meaningful</li>
 * </ol>
 *
 * <p>After canonicalization, {@link #sha256(String)} returns the hex-encoded
 * SHA-256 hash of the canonical URL string — this is the stable {@code articleId}.
 *
 * <p>Utility class: all methods are static, no instances needed.
 */
public final class UrlCanonicalizer {

    /** Tracking-only query parameters that carry no semantic meaning. */
    private static final Set<String> TRACKING_PARAMS = Set.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "fbclid", "gclid", "mc_cid", "mc_eid"
    );

    private UrlCanonicalizer() {}

    /**
     * Returns the canonical form of {@code rawUrl}.
     * If the URL cannot be parsed, returns the original string unchanged.
     */
    public static String canonicalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return rawUrl;
        try {
            URI uri = new URI(rawUrl);

            // 1. Lowercase host
            String host = uri.getHost() != null ? uri.getHost().toLowerCase() : uri.getHost();

            // 2. Strip fragment — set it to null
            // 3. Remove tracking query params
            String cleanQuery = cleanQuery(uri.getQuery());

            // 4. Strip trailing slashes from path
            String path = uri.getPath();
            if (path != null && path.length() > 1) {
                while (path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
            }

            URI canonical = new URI(
                    uri.getScheme(),
                    uri.getUserInfo(),
                    host,
                    uri.getPort(),
                    path,
                    cleanQuery,   // null if no remaining params
                    null          // fragment stripped
            );
            return canonical.toString();
        } catch (URISyntaxException e) {
            // Malformed URL — return unchanged rather than crashing the pipeline
            return rawUrl;
        }
    }

    /**
     * Returns the hex-encoded SHA-256 of the canonical form of {@code rawUrl}.
     * This is the stable {@code articleId} used as the DynamoDB partition key.
     */
    public static String sha256(String rawUrl) {
        String canonical = canonicalize(rawUrl);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in the JVM spec — this cannot happen
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Removes tracking params from the query string; returns null if no params remain.
     * Uses a TreeMap to produce stable key ordering (makes the hash deterministic
     * regardless of server-side query param order).
     */
    private static String cleanQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return null;

        TreeMap<String, String> kept = new TreeMap<>();
        for (String pair : rawQuery.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = kv[0];
            if (!TRACKING_PARAMS.contains(key.toLowerCase())) {
                kept.put(key, kv.length > 1 ? kv[1] : "");
            }
        }
        if (kept.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        kept.forEach((k, v) -> {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(k);
            if (!v.isEmpty()) sb.append('=').append(v);
        });
        return sb.toString();
    }
}
