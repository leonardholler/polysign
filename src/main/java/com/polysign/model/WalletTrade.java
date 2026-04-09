package com.polysign.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

import java.math.BigDecimal;

/**
 * DynamoDB "wallet_trades" table.
 *
 * PK: address   (S) — proxy wallet address (lowercase)
 * SK: txHash    (S) — on-chain transaction hash; natural idempotency key.
 *                     Using txHash as SK (not timestamp) guarantees that
 *                     re-processing the same trade from the Data API never
 *                     creates a duplicate row, even if a wallet makes two
 *                     on-chain trades in the same second (same timestamp, different txHash).
 *
 * GSI: marketId-timestamp-index  PK=marketId  SK=timestamp
 *      Used by ConsensusDetector to find all watched-wallet trades on a
 *      market within the last 30 minutes — the query that triggers the
 *      consensus alert.
 *
 *      NOTE: timestamp is stored as the ISO-8601 string of the trade's
 *      on-chain Unix epoch second (e.g. "2026-04-09T12:00:00Z").
 *      Lexicographic ordering of ISO-8601 UTC strings matches chronological
 *      order, so sortBetween range queries on the GSI work correctly.
 */
@DynamoDbBean
public class WalletTrade {

    private String address;
    private String txHash;      // table SK — idempotency key
    private String timestamp;   // ISO-8601 of on-chain trade time — GSI SK
    private String marketId;    // Gamma numeric market ID — GSI PK
    private String marketQuestion;
    private String side;        // "BUY" | "SELL"
    private String outcome;     // "YES" | "NO" (or "Up"/"Down" for short-duration markets)
    private BigDecimal sizeUsdc;
    private BigDecimal price;
    private String slug;        // Polymarket event slug for deep links

    @DynamoDbPartitionKey
    public String getAddress() { return address; }

    /** Table SK — transaction hash, the natural idempotency key per trade. */
    @DynamoDbSortKey
    public String getTxHash() { return txHash; }

    /** GSI PK — used by ConsensusDetector's marketId-timestamp-index query. */
    @DynamoDbSecondaryPartitionKey(indexNames = "marketId-timestamp-index")
    public String getMarketId() { return marketId; }

    /** GSI SK — ISO-8601 timestamp for range queries in the consensus window. */
    @DynamoDbSecondarySortKey(indexNames = "marketId-timestamp-index")
    public String getTimestamp() { return timestamp; }

    public String     getMarketQuestion() { return marketQuestion; }
    public String     getSide()           { return side;           }
    public String     getOutcome()        { return outcome;        }
    public BigDecimal getSizeUsdc()       { return sizeUsdc;       }
    public BigDecimal getPrice()          { return price;          }
    public String     getSlug()           { return slug;           }

    public void setAddress(String address)               { this.address        = address;        }
    public void setTxHash(String txHash)                 { this.txHash         = txHash;         }
    public void setTimestamp(String timestamp)           { this.timestamp      = timestamp;      }
    public void setMarketId(String marketId)             { this.marketId       = marketId;       }
    public void setMarketQuestion(String marketQuestion) { this.marketQuestion = marketQuestion; }
    public void setSide(String side)                     { this.side           = side;           }
    public void setOutcome(String outcome)               { this.outcome        = outcome;        }
    public void setSizeUsdc(BigDecimal sizeUsdc)         { this.sizeUsdc       = sizeUsdc;       }
    public void setPrice(BigDecimal price)               { this.price          = price;          }
    public void setSlug(String slug)                     { this.slug           = slug;           }
}
