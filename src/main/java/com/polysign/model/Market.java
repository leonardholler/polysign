package com.polysign.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

import java.util.List;
import java.util.Set;

/**
 * DynamoDB "markets" table.
 *
 * PK:  marketId (S)
 * GSI: category-updatedAt-index  PK=category  SK=updatedAt
 */
@DynamoDbBean
public class Market {

    private String marketId;
    private String question;
    private String category;
    private String endDate;
    private String volume;
    private List<String> outcomes;
    private Set<String> keywords;
    private Boolean isWatched;
    private String updatedAt;

    // ── Key / index getters (annotated) ───────────────────────────────────────

    @DynamoDbPartitionKey
    public String getMarketId() { return marketId; }

    @DynamoDbSecondaryPartitionKey(indexNames = "category-updatedAt-index")
    public String getCategory() { return category; }

    @DynamoDbSecondarySortKey(indexNames = "category-updatedAt-index")
    public String getUpdatedAt() { return updatedAt; }

    // ── Plain getters ─────────────────────────────────────────────────────────

    public String getQuestion()       { return question;  }
    public String getEndDate()        { return endDate;   }
    public String getVolume()         { return volume;    }
    public List<String> getOutcomes() { return outcomes;  }
    public Set<String> getKeywords()  { return keywords;  }
    public Boolean getIsWatched()     { return isWatched; }

    // ── Setters ───────────────────────────────────────────────────────────────

    public void setMarketId(String marketId)       { this.marketId   = marketId;   }
    public void setQuestion(String question)       { this.question   = question;   }
    public void setCategory(String category)       { this.category   = category;   }
    public void setEndDate(String endDate)         { this.endDate    = endDate;    }
    public void setVolume(String volume)           { this.volume     = volume;     }
    public void setOutcomes(List<String> outcomes) { this.outcomes   = outcomes;   }
    public void setKeywords(Set<String> keywords)  { this.keywords   = keywords;   }
    public void setIsWatched(Boolean isWatched)    { this.isWatched  = isWatched;  }
    public void setUpdatedAt(String updatedAt)     { this.updatedAt  = updatedAt;  }
}
