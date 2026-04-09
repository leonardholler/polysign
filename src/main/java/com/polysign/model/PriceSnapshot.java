package com.polysign.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

import java.math.BigDecimal;

/**
 * DynamoDB "price_snapshots" table.
 *
 * PK: marketId (S)
 * SK: timestamp (S, ISO-8601 — lexicographically sortable)
 * TTL: expiresAt (epoch seconds, 7 days out)
 *
 * Query pattern: get all snapshots for market X in the last 60 minutes.
 */
@DynamoDbBean
public class PriceSnapshot {

    private String marketId;
    private String timestamp;
    private BigDecimal yesPrice;
    private BigDecimal noPrice;
    private BigDecimal volume24h;
    private BigDecimal midpoint;
    private Long expiresAt;   // TTL — Unix epoch seconds

    @DynamoDbPartitionKey
    public String getMarketId() { return marketId; }

    @DynamoDbSortKey
    public String getTimestamp() { return timestamp; }

    public BigDecimal getYesPrice()  { return yesPrice;  }
    public BigDecimal getNoPrice()   { return noPrice;   }
    public BigDecimal getVolume24h() { return volume24h; }
    public BigDecimal getMidpoint()  { return midpoint;  }
    public Long      getExpiresAt()  { return expiresAt; }

    public void setMarketId(String marketId)      { this.marketId  = marketId;  }
    public void setTimestamp(String timestamp)     { this.timestamp = timestamp; }
    public void setYesPrice(BigDecimal yesPrice)  { this.yesPrice  = yesPrice;  }
    public void setNoPrice(BigDecimal noPrice)     { this.noPrice   = noPrice;   }
    public void setVolume24h(BigDecimal volume24h) { this.volume24h = volume24h; }
    public void setMidpoint(BigDecimal midpoint)  { this.midpoint  = midpoint;  }
    public void setExpiresAt(Long expiresAt)      { this.expiresAt = expiresAt; }
}
