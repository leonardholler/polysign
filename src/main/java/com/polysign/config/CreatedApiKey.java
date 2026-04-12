package com.polysign.config;

import com.polysign.model.Tier;

/**
 * Returned once by {@link ApiKeyRepository#createNew}. Contains the raw key, which
 * is NEVER stored after this point — show it to the client and discard.
 */
public record CreatedApiKey(
        String rawKey,        // show to client once; unrecoverable after
        String apiKeyHash,
        String clientName,
        Tier   tier,
        String keyPrefix,
        String createdAt
) {}
