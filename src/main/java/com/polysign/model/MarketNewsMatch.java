package com.polysign.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

import java.util.Set;

/**
 * DynamoDB "market_news_matches" table.
 *
 * PK: marketId (S)
 * SK: articleId (S)
 */
@DynamoDbBean
public class MarketNewsMatch {

    private String marketId;
    private String articleId;
    private Double score;
    private Set<String> matchedKeywords;
    private String createdAt;

    @DynamoDbPartitionKey
    public String getMarketId() { return marketId; }

    @DynamoDbSortKey
    public String getArticleId() { return articleId; }

    public Double      getScore()           { return score;           }
    public Set<String> getMatchedKeywords() { return matchedKeywords; }
    public String      getCreatedAt()       { return createdAt;       }

    public void setMarketId(String marketId)               { this.marketId        = marketId;        }
    public void setArticleId(String articleId)             { this.articleId       = articleId;       }
    public void setScore(Double score)                     { this.score           = score;           }
    public void setMatchedKeywords(Set<String> keywords)   { this.matchedKeywords = keywords;        }
    public void setCreatedAt(String createdAt)             { this.createdAt       = createdAt;       }
}
