package com.polysign.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * DynamoDB "markets" table.
 *
 * PK:  marketId (S)
 * GSI: category-updatedAt-index  PK=category  SK=updatedAt
 *
 * Key non-schema attributes added beyond the spec baseline:
 *   yesTokenId   — first CLOB token ID (YES outcome), pre-parsed so PricePoller never
 *                  re-parses the clobTokenIds JSON string on every 60-second poll cycle.
 *   clobTokenIds — raw JSON array string preserved for reference.
 *   volume24h    — 24-hour volume from Gamma API 'volume24hr' field.
 */
@DynamoDbBean
public class Market {

    private String  marketId;
    private String  question;
    private String  category;
    private String  endDate;
    private String  volume;       // lifetime total volume
    private String  volume24h;    // 24-hour volume (Gamma: volume24hr)
    private List<String> outcomes;
    private Set<String>  keywords;
    private Boolean isWatched;
    private String  updatedAt;
    private String  yesTokenId;   // clobTokenIds[0] — YES outcome token for CLOB calls
    private String  clobTokenIds; // raw JSON string: "["<yes_id>","<no_id>"]"
    private String     conditionId;          // Gamma "conditionId" hex hash — matches Data API trade.conditionId
    private BigDecimal currentYesPrice;     // denormalized from latest price_snapshot (updated by PricePoller)
    private String     slug;                // Gamma API market-level "slug" (includes outcome ID suffix)
    private String     eventSlug;           // Gamma API events[0].slug — event-level slug for polymarket.com/event/{eventSlug}
    private Boolean    closed;              // true when market has resolved; populated by closed-market poll (Phase 12+)
    private BigDecimal resolvedOutcomePrice; // final resolved price (0.0 or 1.0); set on resolution

    // ── Key / index getters (annotated) ───────────────────────────────────────

    @DynamoDbPartitionKey
    public String getMarketId() { return marketId; }

    @DynamoDbSecondaryPartitionKey(indexNames = "category-updatedAt-index")
    public String getCategory() { return category; }

    @DynamoDbSecondarySortKey(indexNames = "category-updatedAt-index")
    public String getUpdatedAt() { return updatedAt; }

    // ── Plain getters ─────────────────────────────────────────────────────────

    public String       getQuestion()      { return question;      }
    public String       getEndDate()       { return endDate;       }
    public String       getVolume()        { return volume;        }
    public String       getVolume24h()     { return volume24h;     }
    public List<String> getOutcomes()      { return outcomes;      }
    public Set<String>  getKeywords()      { return keywords;      }
    public Boolean      getIsWatched()     { return isWatched;     }
    public String       getYesTokenId()    { return yesTokenId;    }
    public String       getClobTokenIds()  { return clobTokenIds;  }
    public String       getSlug()             { return slug;             }
    public String       getEventSlug()        { return eventSlug;        }
    @DynamoDbSecondaryPartitionKey(indexNames = "conditionId-index")
    public String       getConditionId()      { return conditionId;      }
    public BigDecimal   getCurrentYesPrice()    { return currentYesPrice;    }
    public Boolean      getClosed()             { return closed;             }
    public BigDecimal   getResolvedOutcomePrice() { return resolvedOutcomePrice; }

    // ── Setters ───────────────────────────────────────────────────────────────

    public void setMarketId(String marketId)          { this.marketId     = marketId;     }
    public void setQuestion(String question)          { this.question     = question;     }
    public void setCategory(String category)          { this.category     = category;     }
    public void setEndDate(String endDate)            { this.endDate      = endDate;      }
    public void setVolume(String volume)              { this.volume       = volume;       }
    public void setVolume24h(String volume24h)        { this.volume24h    = volume24h;    }
    public void setOutcomes(List<String> outcomes)    { this.outcomes     = outcomes;     }
    public void setKeywords(Set<String> keywords)     { this.keywords     = keywords;     }
    public void setIsWatched(Boolean isWatched)       { this.isWatched    = isWatched;    }
    public void setUpdatedAt(String updatedAt)        { this.updatedAt    = updatedAt;    }
    public void setYesTokenId(String yesTokenId)      { this.yesTokenId   = yesTokenId;   }
    public void setClobTokenIds(String clobTokenIds)  { this.clobTokenIds = clobTokenIds; }
    public void setSlug(String slug)                                    { this.slug                  = slug;                  }
    public void setEventSlug(String eventSlug)                         { this.eventSlug             = eventSlug;             }
    public void setConditionId(String conditionId)                      { this.conditionId           = conditionId;           }
    public void setCurrentYesPrice(BigDecimal currentYesPrice)         { this.currentYesPrice        = currentYesPrice;        }
    public void setClosed(Boolean closed)                               { this.closed                = closed;                }
    public void setResolvedOutcomePrice(BigDecimal resolvedOutcomePrice) { this.resolvedOutcomePrice  = resolvedOutcomePrice;  }
}
