package com.polysign.api.v1.dto;

import java.math.BigDecimal;

/** Price snapshot DTO for the {@code /api/v1/snapshots} endpoint. */
public record SnapshotV1Dto(
        String marketId,
        String timestamp,
        BigDecimal yesPrice,
        BigDecimal noPrice,
        BigDecimal volume24h,
        BigDecimal midpoint
) {}
