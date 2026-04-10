package com.polysign.model;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

import java.math.BigDecimal;

/**
 * DynamoDB "alert_outcomes" table.
 *
 * PK:  alertId  (S) — same ID as the row in the alerts table
 * SK:  horizon  (S) — one of "t15m", "t1h", "t24h", "resolution"
 * GSI: type-firedAt-index on (type, firedAt) — for per-detector aggregation
 * TTL: none — outcomes are cheap and the whole point is long-term measurement
 *
 * Writes use attribute_not_exists(horizon) on the composite key so that running
 * the evaluator twice on the same alert cannot produce two outcome rows at the
 * same horizon.
 */
@DynamoDbBean
public class AlertOutcome {

    private String alertId;
    private String horizon;             // "t15m" | "t1h" | "t24h" | "resolution"
    private String type;                // detector type — GSI PK
    private String marketId;
    private String firedAt;             // ISO-8601 — GSI SK
    private String evaluatedAt;         // ISO-8601
    private BigDecimal priceAtAlert;
    private BigDecimal priceAtHorizon;
    private String directionPredicted;  // nullable: "up" | "down"
    private String directionRealized;   // nullable: "up" | "down" | "flat"
    private Boolean wasCorrect;         // nullable (null = flat zone or news_correlation)
    private BigDecimal magnitudePp;
    private BigDecimal spreadBpsAtAlert;   // nullable, informational from alert metadata
    private BigDecimal depthAtMidAtAlert;  // nullable, informational from alert metadata

    // ── Key / index getters ───────────────────────────────────────────────────

    @DynamoDbPartitionKey
    public String getAlertId() { return alertId; }

    @DynamoDbSortKey
    public String getHorizon() { return horizon; }

    @DynamoDbSecondaryPartitionKey(indexNames = "type-firedAt-index")
    public String getType() { return type; }

    @DynamoDbSecondarySortKey(indexNames = "type-firedAt-index")
    public String getFiredAt() { return firedAt; }

    // ── Plain getters ─────────────────────────────────────────────────────────

    public String     getMarketId()           { return marketId; }
    public String     getEvaluatedAt()        { return evaluatedAt; }
    public BigDecimal getPriceAtAlert()       { return priceAtAlert; }
    public BigDecimal getPriceAtHorizon()     { return priceAtHorizon; }
    public String     getDirectionPredicted() { return directionPredicted; }
    public String     getDirectionRealized()  { return directionRealized; }
    public Boolean    getWasCorrect()         { return wasCorrect; }
    public BigDecimal getMagnitudePp()        { return magnitudePp; }
    public BigDecimal getSpreadBpsAtAlert()   { return spreadBpsAtAlert; }
    public BigDecimal getDepthAtMidAtAlert()  { return depthAtMidAtAlert; }

    // ── Setters ───────────────────────────────────────────────────────────────

    public void setAlertId(String alertId)                         { this.alertId           = alertId; }
    public void setHorizon(String horizon)                         { this.horizon           = horizon; }
    public void setType(String type)                               { this.type              = type; }
    public void setMarketId(String marketId)                       { this.marketId          = marketId; }
    public void setFiredAt(String firedAt)                         { this.firedAt           = firedAt; }
    public void setEvaluatedAt(String evaluatedAt)                 { this.evaluatedAt       = evaluatedAt; }
    public void setPriceAtAlert(BigDecimal priceAtAlert)           { this.priceAtAlert      = priceAtAlert; }
    public void setPriceAtHorizon(BigDecimal priceAtHorizon)       { this.priceAtHorizon    = priceAtHorizon; }
    public void setDirectionPredicted(String directionPredicted)   { this.directionPredicted = directionPredicted; }
    public void setDirectionRealized(String directionRealized)     { this.directionRealized = directionRealized; }
    public void setWasCorrect(Boolean wasCorrect)                  { this.wasCorrect        = wasCorrect; }
    public void setMagnitudePp(BigDecimal magnitudePp)             { this.magnitudePp       = magnitudePp; }
    public void setSpreadBpsAtAlert(BigDecimal spreadBpsAtAlert)   { this.spreadBpsAtAlert  = spreadBpsAtAlert; }
    public void setDepthAtMidAtAlert(BigDecimal depthAtMidAtAlert) { this.depthAtMidAtAlert = depthAtMidAtAlert; }
}
