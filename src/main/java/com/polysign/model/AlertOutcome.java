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
    private String category;               // Polymarket market category (e.g. "Politics"); null for pre-Phase-13 rows

    // ── Skill-metric fields (Phase signal-quality-overhaul) ───────────────────
    // null on old rows; treat as: scorable=true, deadZone=false, brierSkill excluded from aggregates.

    /** false = priceAtAlert was null/zero at evaluation time — no baseline exists.
     *  null  = old row, treat as scorable (backward compat). */
    private Boolean scorable;
    /** "no_baseline" when scorable=false; null otherwise. */
    private String  skipReason;
    /** true when priceAtAlert &lt; 0.10 or &gt; 0.90 — direction near-predetermined. */
    private Boolean deadZone;
    /** (priceAtAlert − actual)² where actual = 1 if priceAtHorizon ≥ 0.50 else 0. */
    private BigDecimal marketBrier;
    /** (detectorProbYes − actual)² where detectorProbYes = priceAtAlert ± 0.20. */
    private BigDecimal detectorBrier;
    /** marketBrier − detectorBrier — positive means detector beat the market's implied probability. */
    private BigDecimal brierSkill;

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
    public String     getCategory()           { return category; }
    public Boolean    getScorable()           { return scorable; }
    public String     getSkipReason()         { return skipReason; }
    public Boolean    getDeadZone()           { return deadZone; }
    public BigDecimal getMarketBrier()        { return marketBrier; }
    public BigDecimal getDetectorBrier()      { return detectorBrier; }
    public BigDecimal getBrierSkill()         { return brierSkill; }

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
    public void setCategory(String category)                       { this.category          = category; }
    public void setScorable(Boolean scorable)                      { this.scorable          = scorable; }
    public void setSkipReason(String skipReason)                   { this.skipReason        = skipReason; }
    public void setDeadZone(Boolean deadZone)                      { this.deadZone          = deadZone; }
    public void setMarketBrier(BigDecimal marketBrier)             { this.marketBrier       = marketBrier; }
    public void setDetectorBrier(BigDecimal detectorBrier)         { this.detectorBrier     = detectorBrier; }
    public void setBrierSkill(BigDecimal brierSkill)               { this.brierSkill        = brierSkill; }
}
