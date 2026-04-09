package com.polysign.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

import java.util.Set;

/**
 * DynamoDB "articles" table.
 *
 * PK: articleId (S, SHA-256 of URL)
 */
@DynamoDbBean
public class Article {

    private String articleId;   // SHA-256 of URL
    private String title;
    private String url;
    private String source;
    private String publishedAt;
    private String summary;
    private Set<String> keywords;
    private String s3Key;       // key in polysign-archives bucket

    @DynamoDbPartitionKey
    public String getArticleId() { return articleId; }

    public String      getTitle()       { return title;       }
    public String      getUrl()         { return url;         }
    public String      getSource()      { return source;      }
    public String      getPublishedAt() { return publishedAt; }
    public String      getSummary()     { return summary;     }
    public Set<String> getKeywords()    { return keywords;    }
    public String      getS3Key()       { return s3Key;       }

    public void setArticleId(String articleId)     { this.articleId   = articleId;   }
    public void setTitle(String title)             { this.title       = title;       }
    public void setUrl(String url)                 { this.url         = url;         }
    public void setSource(String source)           { this.source      = source;      }
    public void setPublishedAt(String publishedAt) { this.publishedAt = publishedAt; }
    public void setSummary(String summary)         { this.summary     = summary;     }
    public void setKeywords(Set<String> keywords)  { this.keywords    = keywords;    }
    public void setS3Key(String s3Key)             { this.s3Key       = s3Key;       }
}
