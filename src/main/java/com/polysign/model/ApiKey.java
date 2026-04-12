package com.polysign.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

/**
 * DynamoDB "api_keys" table.
 *
 * PK: apiKeyHash (S) — SHA-256 hex digest of the raw key. Raw key is NEVER stored.
 *
 * The raw key is shown to the client once at creation and is unrecoverable after
 * (AWS/GitHub PAT model). Authentication hashes the presented key and looks up this record.
 */
@DynamoDbBean
public class ApiKey {

    private String  apiKeyHash;   // SHA-256 hex digest — PK
    private String  clientName;
    private String  tier;         // "FREE" | "PRO" — use Tier.valueOf(tier) in code
    private Integer rateLimit;    // requests per minute
    private String  createdAt;    // ISO-8601 UTC string (AppClock.nowIso())
    private Boolean active;
    private String  keyPrefix;    // first 8 chars of raw key, for log identification only

    @DynamoDbPartitionKey
    public String getApiKeyHash() { return apiKeyHash; }

    public String  getClientName() { return clientName; }
    public String  getTier()       { return tier;       }
    public Integer getRateLimit()  { return rateLimit;  }
    public String  getCreatedAt()  { return createdAt;  }
    public Boolean getActive()     { return active;     }
    public String  getKeyPrefix()  { return keyPrefix;  }

    public void setApiKeyHash(String apiKeyHash) { this.apiKeyHash = apiKeyHash; }
    public void setClientName(String clientName) { this.clientName = clientName; }
    public void setTier(String tier)             { this.tier       = tier;       }
    public void setRateLimit(Integer rateLimit)  { this.rateLimit  = rateLimit;  }
    public void setCreatedAt(String createdAt)   { this.createdAt  = createdAt;  }
    public void setActive(Boolean active)        { this.active     = active;     }
    public void setKeyPrefix(String keyPrefix)   { this.keyPrefix  = keyPrefix;  }
}
