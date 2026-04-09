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

    private String address;      // lowercase Ethereum address
    private String alias;
    private String category;     // e.g. "politics", "sports"
    private String notes;
    private String lastSyncedAt; // ISO-8601 — last time WalletPoller processed this wallet

    @DynamoDbPartitionKey
    public String getAddress() { return address; }

    public String getAlias()        { return alias;        }
    public String getCategory()     { return category;     }
    public String getNotes()        { return notes;        }
    public String getLastSyncedAt() { return lastSyncedAt; }

    public void setAddress(String address)           { this.address       = address;       }
    public void setAlias(String alias)               { this.alias         = alias;         }
    public void setCategory(String category)         { this.category      = category;      }
    public void setNotes(String notes)               { this.notes         = notes;         }
    public void setLastSyncedAt(String lastSyncedAt) { this.lastSyncedAt  = lastSyncedAt;  }
}
