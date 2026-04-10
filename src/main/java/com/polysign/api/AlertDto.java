package com.polysign.api;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Enriched alert response DTO.
 *
 * <p>Combines all alert fields with denormalized market context (loaded via a
 * batch Market table lookup — never one query per alert) and the signal-strength
 * count for the alert's market in the last 60 minutes.
 *
 * <p>{@code metadataHighlight} is the single most informative value from
 * {@code metadata} for quick scanning without opening the full alert:
 * <ul>
 *   <li>price_movement      → "+8.2%" or "−3.1%" (movePct with sign)</li>
 *   <li>statistical_anomaly → "z=3.7"</li>
 *   <li>consensus           → "3 wallets"</li>
 *   <li>news_correlation    → "score=0.72"</li>
 *   <li>wallet_activity     → "$12,400"</li>
 * </ul>
 */
public record AlertDto(
        String alertId,
        String createdAt,
        String type,
        String severity,
        String marketId,
        String title,
        String description,
        Map<String, String> metadata,
        Boolean wasNotified,
        Boolean phoneWorthy,
        Boolean reviewed,
        String link,

        // Decision 7 — market context (from a single Market row per unique marketId)
        String marketQuestion,
        BigDecimal currentYesPrice,
        String volume24h,
        String marketSlug,         // Polymarket event slug for direct links

        // Signal strength — count of distinct detector types on this market in last 60 min
        int signalStrength,
        boolean badge,             // true when signalStrength >= 3

        // Decision 7 — type-specific metadata highlight for the feed row
        String metadataHighlight,

        // news_correlation score (from metadata); null for other alert types
        Double score
) {}
