package com.polysign.wallet;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.math.BigDecimal;

/**
 * DynamoDB "wallet_metadata" table.
 *
 * PK: address (S) — proxy wallet address (lowercase)
 *
 * Caches lifetime activity summary for a wallet address, populated by
 * WalletMetadataService on first access and refreshed every 6 hours.
 * TTL is managed via expiresAt (epoch seconds).
 */
@DynamoDbBean
public class WalletMetadata {

    private String     address;
    private String     firstTradeAt;          // ISO-8601
    private Integer    lifetimeTradeCount;
    private BigDecimal lifetimeVolumeUsd;
    private Long       expiresAt;             // epoch seconds — TTL attribute
    private Boolean    dataUnavailable;       // true if API lookup failed

    @DynamoDbPartitionKey
    public String getAddress()                    { return address;           }
    public String getFirstTradeAt()               { return firstTradeAt;      }
    public Integer getLifetimeTradeCount()        { return lifetimeTradeCount; }
    public BigDecimal getLifetimeVolumeUsd()      { return lifetimeVolumeUsd; }
    public Long getExpiresAt()                    { return expiresAt;         }
    public Boolean getDataUnavailable()           { return dataUnavailable;   }

    public void setAddress(String address)                       { this.address            = address;            }
    public void setFirstTradeAt(String firstTradeAt)             { this.firstTradeAt        = firstTradeAt;       }
    public void setLifetimeTradeCount(Integer lifetimeTradeCount){ this.lifetimeTradeCount  = lifetimeTradeCount; }
    public void setLifetimeVolumeUsd(BigDecimal lifetimeVolumeUsd){ this.lifetimeVolumeUsd  = lifetimeVolumeUsd;  }
    public void setExpiresAt(Long expiresAt)                     { this.expiresAt           = expiresAt;          }
    public void setDataUnavailable(Boolean dataUnavailable)      { this.dataUnavailable     = dataUnavailable;    }

    /**
     * Returns true when the API lookup failed and the metadata is a sentinel stub.
     * Callers should treat unknown metadata as a false-negative rather than firing an alert.
     */
    public boolean isUnknown() {
        return Boolean.TRUE.equals(dataUnavailable);
    }
}
