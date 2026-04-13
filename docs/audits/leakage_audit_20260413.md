# Pre-Phase-2 Leakage Audit — 2026-04-13

**Scope:** Read-only audit of all alert_outcomes and alert rows in the 7-day window.  
**Purpose:** Establish baseline leakage metrics now that Phase B (horizon=resolution) infrastructure exists.  
**Date:** 2026-04-13 ~20:18 UTC  
**Data source:** LocalStack DynamoDB at `http://localhost:4566`

---

## 0. Sample Record Key Confirmation

Before computing any aggregations, one record from each table was scanned to confirm field names.

**alert_outcomes fields (live scan):**
```
alertId, horizon, type, marketId, firedAt, evaluatedAt,
priceAtAlert, priceAtHorizon, directionPredicted, directionRealized,
wasCorrect, magnitudePp, category
```
- Fire time on outcome rows → `firedAt` (ISO-8601)
- Detector name → `type`
- No `effectivelyResolvedAt` field exists on outcome rows

**alerts fields (live scan):**
```
alertId, createdAt, type, marketId, metadata, severity,
title, description, wasNotified, phoneWorthy, link, expiresAt
```
- Fire time on alert rows → `createdAt` (ISO-8601, also the DynamoDB SK)
- Detector name → `type`
- No `firedAt`, no `detector`, no `wasCorrect` on alerts table (those live in alert_outcomes)

> **Field-name rule used throughout this audit:** `firedAt` from alert_outcomes (preferred); fallback to alert `createdAt` when outcome row has no `firedAt`. Detector name is `type` in both tables.

---

## 1. Raw Data Counts

| Table | Count |
|---|---|
| `alert_outcomes` rows (total) | 146 |
| `alerts` rows (total) | 249 |
| `markets` rows (total) | 523 |
| Unique markets with outcome rows | 42 |
| 7-day window coverage | 100% (all rows; system started today) |

### Horizon breakdown

| Horizon | Rows |
|---|---|
| t15m | 100 |
| t1h | 46 |
| t24h | 0 |
| resolution | **0** |

> **Note:** t24h is absent because the system started less than 24 hours ago. AlertOutcomeEvaluator has not yet reached the +24h evaluation window for any alert. This is expected.

---

## 2. Resolution Row Status

**resolution horizon rows: 0**

The Phase B sweeper (`ResolutionSweeper.findEffectivelyResolvedMarkets()`) has not written any resolution outcome rows yet. Root cause:

| Condition | Status |
|---|---|
| Markets with `resolvedBy` set | 0 of 523 |
| Markets with `outcomePrices` set | 0 of 523 |
| Markets with `resolvedOutcomePrice` set (Phase A) | 0 of 523 |
| Markets with any Phase 13 field | **0 of 523** |

Phase B requires `resolvedBy != null AND outcomePrices[0] ≥ 0.99 or ≤ 0.01`. Since no market has Phase 13 fields written yet, the predicate cannot fire for any row. See §7 (Stale Market Analysis) for details.

> The session context noted "2 resolution outcome rows exist" — this is not reflected in the current DB state. Either those rows were not committed to the persistent LocalStack volume, or this audit precedes the expected sweep run.

---

## 3. Dedup Analysis (e)

Checked for duplicate `(alertId, horizon)` composite key rows.

| Metric | Value |
|---|---|
| Raw rows in 7d window | 146 |
| Duplicate (alertId, horizon) pairs removed | **0** |
| Post-dedup rows | 146 |

The idempotent `attribute_not_exists(horizon)` conditional write is working correctly. No duplicates exist.

**Pre-dedup precision vs post-dedup precision:**

| Stage | Total | Scored | Flat/null | Correct | Precision |
|---|---|---|---|---|---|
| Pre-dedup (all horizons) | 146 | 106 | 40 | 40 | **37.7%** |
| Post-dedup (all horizons) | 146 | 106 | 40 | 40 | **37.7%** |

Precision is identical — deduplication had no effect on the denominator.

### Per-horizon precision (post-dedup)

| Horizon | Total | Scored | Flat/null | Correct | Precision |
|---|---|---|---|---|---|
| t15m | 100 | 80 | 20 | 32 | **40.0%** |
| t1h | 46 | 26 | 20 | 8 | **30.8%** |

Signal decays from t15m → t1h (see §5 for matched-alert decay analysis).

---

## 4. Price Movement: Fire → Horizon (b)

| Horizon | n | avg Δp | >10pp moves | >30pp moves |
|---|---|---|---|---|
| t15m | 100 | **+0.009** | 26 (26%) | 5 (5%) |
| t1h | 46 | **−0.013** | 10 (22%) | 5 (11%) |

The average Δp flips sign from t15m (+0.9pp) to t1h (−1.3pp), consistent with short-lived momentum followed by mean reversion. Signals that move the market quickly at 15 minutes tend to overshoot and partially reverse by 1 hour.

---

## 5. Lead-Time Histogram: Fire → Resolution Anchor (c)

Resolution anchor used: `gameStartTime` if present, else `endDate`. No market has `gameStartTime` persisted yet (all absent), so `endDate` is the anchor for all 106 scored signals.

| Bucket | Count | Fraction |
|---|---|---|
| ≤15 min before endDate | 15 | 14.2% |
| ≤1 hour before endDate | 0 | 0.0% |
| ≤4 hours before endDate | 0 | 0.0% |
| ≤24 hours before endDate | 12 | 11.3% |
| >24 hours before endDate | 79 | 74.5% |
| No anchor available | 0 | — |

**Lead-time stats:** avg=24,959 min (≈17 days), min=−30 min, max=903,529 min (≈627 days)

> **Anchor caveat:** `endDate` is the Polymarket-listed market expiry, not the actual game start. For sports markets, games typically resolve hours to days before `endDate`. Until `gameStartTime` is persisted by MarketPoller and surfaced in the audit, the lead-time buckets for sports markets reflect time-to-nominal-expiry, not time-to-actual-resolution. The ≤15m bucket and negative-lead signals below are the exception: they indicate real leakage around actual match end.

### Negative lead-time signals (fired AFTER endDate)

**15 scored signals fired after their market's `endDate`.** All are football/soccer matches that ended at 19:00 UTC and had alerts continue firing past that time:

| Market | Question | endDate | Fire range | Outcomes |
|---|---|---|---|---|
| 1800030 | Will Manchester United FC win on 2026-04-13? | 2026-04-13T19:00Z | +6 to +30 min | 12 |
| 1801185 | Will Levante UD win on 2026-04-13? | 2026-04-13T19:00Z | +14 min | 2 |
| 1801187 | Will Getafe CF win on 2026-04-13? | 2026-04-13T19:00Z | +14 min | 1 |

These markets last appeared in the top-400 poll ~799 min ago (13+ hours) and have not been re-fetched since. The detectors fired on price movements that occurred in-game (or post-result), when the market was nominally still "open" from the DB's perspective. This is the core **temporal leakage** pattern: signals fired on already-decided information.

---

## 6. Extreme-Zone Firing (d)

Markets already at p ≥ 0.90 or p ≤ 0.10 at alert fire time carry much lower information content.

| Zone | Signals (deduped) | Scored | Correct | Precision |
|---|---|---|---|---|
| Extreme (p ≥ 0.9 or ≤ 0.1) | 49 (33.6%) | 27 | 6 | **22.2%** |
| Normal (0.1 < p < 0.9) | 97 (66.4%) | 79 | 34 | **43.0%** |

Extreme-zone precision (22.2%) is roughly half of normal-zone precision (43.0%). One-third of all signals are firing in the extreme zone. This is a strong leakage indicator: detectors are triggering on markets that have already moved past the information-relevant range.

### Extreme-zone rate by detector

| Detector | Total | Extreme-zone | Extreme rate |
|---|---|---|---|
| wallet_activity | 106 | 41 | **38.7%** |
| price_movement | 40 | 8 | **20.0%** |

`wallet_activity` drives the majority of extreme-zone fires.

---

## 7. Per-Detector Breakdown (post-dedup, all horizons)

| Detector | Total | Scored | Flat/null | Correct | Precision | Extreme-zone |
|---|---|---|---|---|---|---|
| price_movement | 40 | 35 | 5 | 20 | **57.1%** | 8 (20%) |
| wallet_activity | 106 | 71 | 35 | 20 | **28.2%** | 41 (39%) |

`price_movement` at 57.1% is above coin-flip. `wallet_activity` at 28.2% is meaningfully below coin-flip. The wallet detector fires large fractions of its alerts on markets already at extreme prices (39% extreme-zone rate), dragging precision down. This is expected for whale-trade detection: large wallets often bet on near-certain outcomes.

---

## 8. Montgomery / Sherif Deep-Dive (market 1959596)

**Market:** Oeiras 3: Robin Montgomery vs Mayar Sherif  
**endDate:** 2026-04-20T09:00:00Z  
**gameStartTime:** absent  
**closed:** false | **resolvedBy:** absent | **outcomePrices:** absent  
**currentYesPrice:** 0.9995 (from CLOB, via denormalized `currentYesPrice` column)

| Field | Status |
|---|---|
| Phase 13 fields written | No |
| Resolvable via Phase B today | No — `resolvedBy` absent |
| Resolution horizon rows | 0 |
| Last MarketPoller refresh | ~135 min ago (18:03:10 UTC) |

**Alerts:** 7 total | **Outcome rows (deduped):** 4 (all t15m, no t1h yet)

| alertId (prefix) | Horizon | Detector | pAtAlert | pAtHorizon | Predicted→Realized | Correct |
|---|---|---|---|---|---|---|
| 6a8a3b6cbfdd… | t15m | price_movement | 0.53 | 0.635 | up→up | ✓ |
| bb8598fdd1f2… | t15m | price_movement | **0.01** | 0.6765 | down→up | ✗ |
| 3b032974b769… | t15m | wallet_activity | 0.635 | 0.9995 | up→up | ✓ |
| 350c2020830a… | t15m | wallet_activity | 0.635 | 0.9995 | up→up | ✓ |

**Leakage flag:** `bb8598fdd1f2…` is a `price_movement` alert that fired at `pAtAlert = 0.01` (deep NO zone) with `directionPredicted = "down"`. The market then moved sharply to 0.6765, making this a large false negative. Firing a "down" prediction on a market already at 1% is a textbook extreme-zone leakage case — the 0.01 price contained no new directional information.

**Why no resolution row yet:**  
Although `currentYesPrice = 0.9995` (from CLOB snapshots), the `outcomePrices` field sourced from the Gamma markets API is absent. Phase B's `effectivelyResolved()` predicate checks `outcomePrices[0]`, not `currentYesPrice`. The Gamma API has not yet set `outcomePrices` for this market. Resolution outcome rows cannot be written until a subsequent MarketPoller sweep retrieves `outcomePrices` from Gamma with a decisive value AND `resolvedBy` is set.

---

## 9. Stale Market Analysis

### Phase 13 field coverage

**0 of 523 markets in the DB have any Phase 13 field** (`resolvedBy`, `outcomePrices`, `active`, `acceptingOrders`). This is the proximate reason Phase B has written 0 resolution rows.

This does not indicate a code bug. Phase 13 fields are only populated by the Gamma API when a market enters the resolution process. For `closed=false` markets with `resolvedBy=absent`, these fields are legitimately null.

### Two tiers of staleness

**Tier 1 — Live markets, no resolution fields (expected):**  
Markets recently polled by MarketPoller (within the last few minutes) that simply haven't entered resolution. Their rows are current; they lack Phase 13 fields because Gamma has not yet assigned a UMA oracle or posted final outcome prices.

Examples (updatedAt within last 10 min): 1507751, 1567746, 1662802, 1929170, 1961412…

**Tier 2 — Dropped from top-400 volume, not re-fetched (at-risk):**  
Markets that fell out of the top-400 24h volume pool. They will NOT get Phase 13 fields until they re-enter the poll or the sweeper's `pollAndStoreClosedMarkets()` fetches them specifically.

| Market | Last polled | Question |
|---|---|---|
| 1800030 | 799 min ago | Will Manchester United FC win on 2026-04-13? |
| 1801185 | 799 min ago | Will Levante UD win on 2026-04-13? |
| 1801187 | 799 min ago | Will Getafe CF win on 2026-04-13? |
| 1947873 | 751 min ago | Busan: Yu-Hsiou Hsu vs Adam Walton |
| 1842267 | 693 min ago | Counter-Strike: B8 vs Natus Vincere |
| 1908559 | 691 min ago | LoL: GIANTX vs Shifters |
| 1823782 | 691 min ago | Will Bitcoin dip to $50,000 in April? |
| 1797341 | 694 min ago | Will the Kharg Island oil terminal be hit? |
| 1797309 | 893 min ago | Kharg Island no longer under Iranian control |
| 1706299 | 893 min ago | Will Iran strike Iraq by April 30, 2026? |
| 706279 | 893 min ago | Will Trump visit China by April 30? |
| 906972 | 893 min ago | Will the Fed decrease interest rates? |
| 910412 | 893 min ago | Will Iran strike Kuwait by April 30, 2026? |

These 13 markets have existing alert outcome rows and are at risk of never receiving resolution horizon scoring unless explicitly re-fetched. The three football markets (1800030, 1801185, 1801187) almost certainly already resolved — their endDate was 19:00 UTC today, and they've been missing from the top-400 poll since ~07:00 UTC.

### Alert-level stale count

| Metric | Count |
|---|---|
| Total alerts | 249 |
| Against stale markets (no Phase 13 fields) | **249** |
| Against markets with Phase 13 fields | 0 |

All 249 current alerts are against markets that cannot yet produce resolution horizon rows. This is expected for day-zero data — no market has formally resolved via the UMA oracle flow during this session.

---

## 10. Signal Decay: t15m → t1h

For the 30 alerts that have both a t15m and t1h outcome row:

| Horizon | Scored | Correct | Precision |
|---|---|---|---|
| t15m (matched set) | 23 | 9 | **39.1%** |
| t1h (matched set) | 21 | 6 | **28.6%** |

10.5pp precision drop from t15m to t1h on the same signals. Combined with the avg Δp sign flip (+0.009 → −0.013), this confirms that detected price movements are short-lived momentum rather than persistent directional trends.

---

## 11. Summary of Key Findings

| Finding | Detail |
|---|---|
| **Phase B blocked** | 0 resolution rows. All 523 markets lack Phase 13 fields. Unblocks when Gamma API sets `resolvedBy` + decisive `outcomePrices` for any tracked market. |
| **No duplicates** | 0 duplicate (alertId, horizon) pairs. Idempotent write logic is correct. |
| **Overall precision** | 37.7% (all horizons, post-dedup). Above coin-flip at t15m (40%), below at t1h (31%). |
| **Mean reversion** | avg Δp flips from +0.9pp (t15m) to −1.3pp (t1h). Signals are short-lived. |
| **Extreme-zone leakage** | 33.6% of signals fired at p≥0.9 or p≤0.1. Extreme-zone precision 22.2% vs normal 43.0%. |
| **Temporal leakage** | 15 scored outcomes fired after market endDate (Man Utd, Levante, Getafe). These markets dropped from top-400 poll and stale rows persisted detectable prices post-resolution. |
| **wallet_activity underperforms** | 28.2% precision, 39% extreme-zone rate. price_movement at 57.1% is strong for a day-0 dataset. |
| **Montgomery/Sherif** | Present (market 1959596). 3 of 4 outcome rows correct. One direct leakage case: price_movement fired "down" at p=0.01. No resolution row possible until Gamma sets outcomePrices. |
| **Stale markets** | 13 markets dropped from top-400 (last poll 691–893 min ago). 3 football markets almost certainly resolved. Scoring these requires explicit re-fetch or a dedicated closed-market poll. |

---

*Generated from live LocalStack DynamoDB scan. Read-only — no changes to scoring, sweeper, or any application code.*
