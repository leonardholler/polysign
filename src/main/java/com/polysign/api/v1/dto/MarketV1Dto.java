package com.polysign.api.v1.dto;

import java.math.BigDecimal;

/** Market DTO for the {@code /api/v1/markets} endpoint. */
public record MarketV1Dto(
        String marketId,
        String question,
        String category,
        String endDate,
        String volume24h,
        BigDecimal currentYesPrice,
        String slug
) {}
