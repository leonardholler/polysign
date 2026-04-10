package com.polysign.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

import java.util.Set;

/**
 * DynamoDB "market_news_matches" table.
 *
 * PK:  marketId  (S)
 * SK:  articleId (S)
 * GSI: articleId-index — PK=articleId, projection ALL.
 *      Enables reverse lookup "which markets matched this article?"
 *      (used by Phase 7.5 backtesting and Phase 8 API endpoints).
 *
 * {@code articleTitle} and {@code articleUrl} are denormalized here to avoid
 * N+1 reads against the articles table when rendering the market news panel.
 */
@DynamoDbBean
public class MarketNewsMatch {

    private String marketId;
    private String articleId;
    private Double score;
    private Set<String> matchedKeywords;
    private String createdAt;
    private String articleTitle;  // denormalized from Article
    private String articleUrl;    // denormalized from Article

    @DynamoDbPartitionKey
    public String getMarketId() { return marketId; }

    @DynamoDbSortKey
    @DynamoDbSecondaryPartitionKey(indexNames = "articleId-index")
    public String getArticleId() { return articleId; }

    public Double      getScore()           { return score;           }
    public Set<String> getMatchedKeywords() { return matchedKeywords; }
    public String      getCreatedAt()       { return createdAt;       }
    public String      getArticleTitle()    { return articleTitle;    }
    public String      getArticleUrl()      { return articleUrl;      }

    public void setMarketId(String marketId)               { this.marketId        = marketId;        }
    public void setArticleId(String articleId)             { this.articleId       = articleId;       }
    public void setScore(Double score)                     { this.score           = score;           }
    public void setMatchedKeywords(Set<String> keywords)   { this.matchedKeywords = keywords;        }
    public void setCreatedAt(String createdAt)             { this.createdAt       = createdAt;       }
    public void setArticleTitle(String articleTitle)       { this.articleTitle    = articleTitle;    }
    public void setArticleUrl(String articleUrl)           { this.articleUrl      = articleUrl;      }
}
