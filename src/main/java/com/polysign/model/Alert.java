package com.polysign.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

import java.util.Map;

/**
 * DynamoDB "alerts" table.
 *
 * PK:  alertId   (S, deterministic SHA-256 — never UUID.randomUUID())
 * SK:  createdAt (S, ISO-8601) — also the GSI SK
 * GSI: marketId-createdAt-index  PK=marketId  SK=createdAt
 * TTL: expiresAt (epoch seconds, 30 days out)
 *
 * Writes use PutItem with attribute_not_exists(alertId) for idempotency.
 */
@DynamoDbBean
public class Alert {

    private String alertId;
    private String createdAt;
    private String type;        // "price_movement" | "statistical_anomaly" | "consensus" | "news_correlation"
    private String severity;    // "info" | "warning" | "critical"
    private String marketId;
    private String title;
    private String description;
    private Map<String, String> metadata;
    private Boolean wasNotified;
    private Boolean phoneWorthy; // set by NotificationConsumer via PhoneWorthinessFilter
    private Boolean reviewed;    // set by POST /api/alerts/{alertId}/mark-reviewed
    private String link;
    private Long expiresAt;     // TTL — Unix epoch seconds (30 days out)

    @DynamoDbPartitionKey
    public String getAlertId() { return alertId; }

    /** Table SK and GSI SK — annotations stacked on one getter, one DynamoDB attribute. */
    @DynamoDbSortKey
    @DynamoDbSecondarySortKey(indexNames = "marketId-createdAt-index")
    public String getCreatedAt() { return createdAt; }

    @DynamoDbSecondaryPartitionKey(indexNames = "marketId-createdAt-index")
    public String getMarketId() { return marketId; }

    public String              getType()        { return type;        }
    public String              getSeverity()    { return severity;    }
    public String              getTitle()       { return title;       }
    public String              getDescription() { return description; }
    public Map<String, String> getMetadata()    { return metadata;    }
    public Boolean             getWasNotified()  { return wasNotified;  }
    public Boolean             getPhoneWorthy()  { return phoneWorthy;  }
    public Boolean             getReviewed()     { return reviewed;     }
    public String              getLink()         { return link;         }
    public Long                getExpiresAt()    { return expiresAt;    }

    public void setAlertId(String alertId)                { this.alertId     = alertId;     }
    public void setCreatedAt(String createdAt)            { this.createdAt   = createdAt;   }
    public void setType(String type)                      { this.type        = type;        }
    public void setSeverity(String severity)              { this.severity    = severity;    }
    public void setMarketId(String marketId)              { this.marketId    = marketId;    }
    public void setTitle(String title)                    { this.title       = title;       }
    public void setDescription(String description)        { this.description = description; }
    public void setMetadata(Map<String, String> metadata) { this.metadata    = metadata;    }
    public void setWasNotified(Boolean wasNotified)        { this.wasNotified  = wasNotified;  }
    public void setPhoneWorthy(Boolean phoneWorthy)        { this.phoneWorthy  = phoneWorthy;  }
    public void setReviewed(Boolean reviewed)              { this.reviewed     = reviewed;     }
    public void setLink(String link)                       { this.link         = link;         }
    public void setExpiresAt(Long expiresAt)               { this.expiresAt    = expiresAt;    }
}
