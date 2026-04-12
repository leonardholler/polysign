package com.polysign.api.v1.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Alert DTO for the {@code /api/v1/alerts} endpoint.
 * Includes {@code signalStrength} (distinct detectors on this market in last 60 min)
 * and {@code phoneWorthy} (set by the notification pipeline).
 */
public record AlertV1Dto(
        String alertId,
        String createdAt,
        String type,
        String severity,
        String marketId,
        String title,
        String description,
        Map<String, String> metadata,
        Boolean phoneWorthy,
        String link,
        int signalStrength
) {}
