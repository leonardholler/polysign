package com.polysign.alert;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Deterministic alert ID generator.
 *
 * <p>{@code alertId = SHA-256(type | marketId | bucketedTimestamp | canonicalPayloadHash)}
 *
 * <p>The bucketed timestamp is the detection instant rounded down to the nearest
 * dedupe-window boundary (epoch seconds). Events within the same window for the
 * same market and type produce the same alert ID, which the
 * {@code attribute_not_exists(alertId)} condition in DynamoDB silently deduplicates.
 *
 * <p>When a detector bypasses the dedupe window (e.g., an extreme move exceeding
 * 2x threshold), it passes {@link Duration#ZERO}, which leaves the timestamp
 * unbucketed — each second produces a distinct ID.
 */
public final class AlertIdFactory {

    private static final char SEPARATOR = '|';

    private AlertIdFactory() {}

    /**
     * Generate a deterministic alert ID without a payload hash.
     *
     * <p>Delegates to the 5-arg overload with an empty string ({@code ""}) as the
     * {@code canonicalPayloadHash}. All detectors that do not need a payload
     * disambiguator must use this overload — or, equivalently, pass {@code ""}
     * explicitly — so that the same inputs always land in the same ID space.
     * Mixing this overload with a non-empty payload hash for the same
     * (type, marketId, window) triple will produce different IDs by design.
     */
    public static String generate(String type, String marketId,
                                  Instant timestamp, Duration dedupeWindow) {
        return generate(type, marketId, timestamp, dedupeWindow, "");
    }

    /**
     * Generate a deterministic alert ID.
     *
     * @param type                 alert type, e.g. "price_movement"
     * @param marketId             the market this alert is about
     * @param timestamp            detection instant
     * @param dedupeWindow         how wide the bucketing window is;
     *                             {@link Duration#ZERO} disables bucketing
     * @param canonicalPayloadHash additional disambiguator (may be empty)
     * @return lowercase hex SHA-256 (64 chars)
     */
    public static String generate(String type, String marketId,
                                  Instant timestamp, Duration dedupeWindow,
                                  String canonicalPayloadHash) {
        long bucketed = bucketTimestamp(timestamp, dedupeWindow);
        String input = type + SEPARATOR
                     + marketId + SEPARATOR
                     + bucketed + SEPARATOR
                     + canonicalPayloadHash;
        return sha256Hex(input);
    }

    /**
     * Return the bucketed instant for a given timestamp and window.
     *
     * <p>This is the canonical "createdAt" value that must be stored alongside
     * the alert ID in the alerts table. Because the alerts table has a composite
     * key (PK=alertId, SK=createdAt), the createdAt must be deterministic for
     * the same alertId — otherwise {@code attribute_not_exists(alertId)} targets
     * a different (PK, SK) slot on each write and never rejects duplicates.
     */
    public static Instant bucketedInstant(Instant timestamp, Duration dedupeWindow) {
        return Instant.ofEpochSecond(bucketTimestamp(timestamp, dedupeWindow));
    }

    /**
     * Round an instant down to the nearest window boundary.
     *
     * <p>A zero-length window ({@link Duration#ZERO}) disables bucketing and
     * returns the raw epoch second, giving 1-second granularity. This means
     * two bypass detections that fire within the same wall-clock second for
     * the same (type, marketId) will collide to the same alert ID. This is
     * acceptable and intentional — sub-second duplicate suppression on extreme
     * moves is a feature, not a bug.
     */
    static long bucketTimestamp(Instant timestamp, Duration dedupeWindow) {
        long epochSeconds = timestamp.getEpochSecond();
        long windowSeconds = dedupeWindow.getSeconds();
        if (windowSeconds <= 0) {
            return epochSeconds;
        }
        return (epochSeconds / windowSeconds) * windowSeconds;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JRE — this cannot happen.
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
