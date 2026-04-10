package com.polysign.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

/**
 * DynamoDB "watched_wallets" table.
 *
 * PK: address (S, lowercase)
 * Loaded at startup from watched_wallets.json (idempotent bootstrap).
 */
@DynamoDbBean
public class WatchedWallet {

    private String address;              // lowercase Ethereum address
    private String alias;
    private String category;             // e.g. "politics", "sports"
    private String notes;
    private String lastSyncedAt;         // ISO-8601 — last time WalletPoller processed this wallet
    private String lastTradeAt;          // ISO-8601 — timestamp of the most recent trade seen
    private Integer tradeCount;          // total trades written in the current sync session
    private String recentDirection;      // "BUY" or "SELL" — direction of most recent trade
    private String lastMarketQuestion;   // question of the market they last traded
    private String lastOutcome;          // "YES" or "NO" — outcome of the most recent trade
    private String lastSizeUsdc;         // dollar amount of the most recent trade (e.g. "2450.00")

    @DynamoDbPartitionKey
    public String getAddress() { return address; }

    public String  getAlias()                { return alias;                }
    public String  getCategory()             { return category;             }
    public String  getNotes()                { return notes;                }
    public String  getLastSyncedAt()         { return lastSyncedAt;         }
    public String  getLastTradeAt()          { return lastTradeAt;          }
    public Integer getTradeCount()           { return tradeCount;           }
    public String  getRecentDirection()      { return recentDirection;      }
    public String  getLastMarketQuestion()   { return lastMarketQuestion;   }
    public String  getLastOutcome()          { return lastOutcome;          }
    public String  getLastSizeUsdc()         { return lastSizeUsdc;         }

    public void setAddress(String address)                       { this.address             = address;             }
    public void setAlias(String alias)                           { this.alias               = alias;               }
    public void setCategory(String category)                     { this.category            = category;            }
    public void setNotes(String notes)                           { this.notes               = notes;               }
    public void setLastSyncedAt(String lastSyncedAt)             { this.lastSyncedAt        = lastSyncedAt;        }
    public void setLastTradeAt(String lastTradeAt)               { this.lastTradeAt         = lastTradeAt;         }
    public void setTradeCount(Integer tradeCount)                { this.tradeCount          = tradeCount;          }
    public void setRecentDirection(String recentDirection)       { this.recentDirection     = recentDirection;     }
    public void setLastMarketQuestion(String lastMarketQuestion) { this.lastMarketQuestion  = lastMarketQuestion;  }
    public void setLastOutcome(String lastOutcome)               { this.lastOutcome         = lastOutcome;         }
    public void setLastSizeUsdc(String lastSizeUsdc)             { this.lastSizeUsdc        = lastSizeUsdc;        }
}
