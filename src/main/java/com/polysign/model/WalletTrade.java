package com.polysign.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

import java.math.BigDecimal;

/**
 * DynamoDB "wallet_trades" table.
 *
 * PK: address (S)
 * SK: timestamp (S, ISO-8601) — also the GSI SK
 * GSI: marketId-timestamp-index  PK=marketId  SK=timestamp
 */
@DynamoDbBean
public class WalletTrade {

    private String address;
    private String timestamp;
    private String marketId;
    private String marketQuestion;
    private String side;       // "BUY" | "SELL"
    private String outcome;    // "YES" | "NO"
    private BigDecimal sizeUsdc;
    private BigDecimal price;
    private String txHash;

    @DynamoDbPartitionKey
    public String getAddress() { return address; }

    /** Table SK and GSI SK — annotations stacked on one getter, one DynamoDB attribute. */
    @DynamoDbSortKey
    @DynamoDbSecondarySortKey(indexNames = "marketId-timestamp-index")
    public String getTimestamp() { return timestamp; }

    @DynamoDbSecondaryPartitionKey(indexNames = "marketId-timestamp-index")
    public String getMarketId() { return marketId; }

    public String     getMarketQuestion() { return marketQuestion; }
    public String     getSide()           { return side;           }
    public String     getOutcome()        { return outcome;        }
    public BigDecimal getSizeUsdc()       { return sizeUsdc;       }
    public BigDecimal getPrice()          { return price;          }
    public String     getTxHash()         { return txHash;         }

    public void setAddress(String address)               { this.address        = address;        }
    public void setTimestamp(String timestamp)           { this.timestamp      = timestamp;      }
    public void setMarketId(String marketId)             { this.marketId       = marketId;       }
    public void setMarketQuestion(String marketQuestion) { this.marketQuestion = marketQuestion; }
    public void setSide(String side)                     { this.side           = side;           }
    public void setOutcome(String outcome)               { this.outcome        = outcome;        }
    public void setSizeUsdc(BigDecimal sizeUsdc)         { this.sizeUsdc       = sizeUsdc;       }
    public void setPrice(BigDecimal price)               { this.price          = price;          }
    public void setTxHash(String txHash)                 { this.txHash         = txHash;         }
}
