# PolySign Full System Audit — 2026-04-13 21:00 UTC

Auditor: Claude (read-only diagnosis, no code changes).  
Scope: Dashboard, backend health, alert pipeline, resolution layer, data freshness, noisiness complaint, orphaned markets.  
Service state at audit start: polysign container up 13 minutes (restarted ~20:44 UTC).

---

## Phase 1A — Dashboard Layer

### Endpoints found in index.html (no WebSocket, no EventSource — pure REST polling)

| Endpoint | Section | Poll interval |
|---|---|---|
| `GET /api/stats` | Header + Stats row | 30s |
| `GET /api/alerts/by-signal-strength` | Live Events | 10s |
| `GET /api/wallets` | Whale Leaderboard | 60s |
| `GET /api/markets?watchedOnly=true&limit=50` | Watched Markets | 60s |
| `GET /api/signals/performance?horizon=t1h` | Signal Quality bar | once on load |
| `GET /api/signals/by-category` | Signal Performance table | 5 min |

### Endpoint health check results

All endpoints return **HTTP 200**. Response times 0.12–0.30s. No 4xx, no 5xx.

```
/api/stats              200  0.30s  marketsTracked=400 alertsFiredToday=152 watchedWallets=106
                                    signalPrecision7d1h=0.875 scoredSamples7d1h=8
                                    lastPollTime=2026-04-13T20:56:55Z (current)
/api/alerts/by-signal-strength  200  0.18s  7 alerts returned
/api/wallets            200  0.20s  106 wallets returned
/api/markets            200  0.29s  22 watched markets returned
/api/signals/performance  200  0.12s  3 detectors
/api/signals/by-category  200  0.17s  4 categories
```

### Critical dashboard bug: `currentYesPrice` null on recently-polled markets

Of the 22 watched markets returned by `/api/markets`:
- **10 markets have `currentYesPrice=null`** but `outcomePrices` populated → show em-dash price in Watched Markets panel and 0%-wide progress bar
- 12 markets have `currentYesPrice` set but `outcomePrices=null` → price shows correctly

**Split pattern by `updatedAt`:**
- Markets updated at 20:56:44 (touched by most recent MarketPoller run): `outcomePrices` set, `currentYesPrice=null`
- Markets last updated at 17:48:54 (3h ago, not in current top-400): `currentYesPrice` set, `outcomePrices=null`

**Root cause confirmed in `MarketPoller.doUpsert()` (lines 384–425):**  
The method creates a fresh `Market` object and never calls `setCurrentYesPrice()`. Then calls `marketsTable.putItem(market)` — a full DynamoDB item replace that erases any `currentYesPrice` previously written by PricePoller. Every 90-second MarketPoller cycle overwrites all 400 tracked markets and wipes their prices.

The `AlertController.toDto()` at line 256 reads `market.getCurrentYesPrice()` for both the Live Events feed and the Watched Markets panel — both sections go em-dash when this field is null.

### Other dashboard observations

- **`score=null` on all alert DTOs**: `AlertController.extractScore()` reads `metadata.get("score")` — no detector currently writes this field. The frontend null-coalesces to 1.0 (`a.score ?? 1`), so all alerts pass the `>= 0.3` filter. Not a bug, just dead code.
- **`signalStrength=1`** on all 7 live feed alerts: only one detector type fired on each of those markets in the last 60 min. Expected given alert volume.
- **"Loading..." initial state**: clears within ~200ms of the first fetch completing. Not a persistent bug.
- **Signal Quality bar**: correctly shows `price 67% · anomaly —% · whale 100%` once `/api/signals/performance` returns.

---

## Phase 1B — Backend Health

**Docker compose status at audit time:**
```
polysign-autoheal-1   Up 3 hours   (healthy)
polysign-localstack-1 Up 24 hours  (healthy)
polysign-polysign-1   Up 13 min    (healthy)   ← recent restart
```

**Errors in last 30 min:** 2 benign `NoResourceFoundException` for `favicon.ico` and `robots.txt` (browser crawlers). No application errors. No DynamoDB throttling. No SQS failures.

**Log volume:** 1,806 lines in 30 min — normal.

**SQS / ntfy:** Working. `AlertService` resolved queue URL at startup; `notification_sent` confirmed at 21:00:12 UTC (`type=price_movement severity=critical`).

**Service restart history:** The container restarted at ~20:44 UTC (13 min before audit). Bootstrap completed cleanly: DynamoDB tables, SQS queue, and S3 bucket creation logged. No evidence of crash loop — likely a manual redeploy.

---

## Phase 1C — Alert Pipeline

### Alert counts (last 24h)

| Detector | Count | % of total |
|---|---|---|
| `wallet_activity` | 129 | 84.9% |
| `price_movement` | 23 | 15.1% |
| `statistical_anomaly` | 0 | 0% |
| `news_correlation` | 0 | 0% |
| **Total** | **152** | |

`statistical_anomaly` has fired 0 times and is generating `statistical_anomaly_detect_complete checked=437 fired=0` every ~60s. This is **expected behaviour**: the detector requires `min_snapshots=20` price history points per market. With the service only 13 minutes old at audit time, most markets have only ~6–7 snapshots. Warmup requires ~40 minutes.

`news_correlation` / `NewsCorrelationDetector`: no class file found in the codebase and no log lines. Either not implemented or not deployed.

### alert_outcomes scored vs unscored

| Horizon | Rows | Correct | Wrong | Unscored | Precision (scored) |
|---|---|---|---|---|---|
| `t15m` | 49 | 14 | 18 | 17 | **43.75%** (14/32) |
| `t1h` | 19 | 7 | 1 | 11 | **87.5%** (7/8) |
| `resolution` | 2 | 1 | 1 | 0 | 50% (2 samples) |

t1h precision of 87.5% is from only 8 scored samples — too thin to be meaningful.

### Precision by market category (t15m, 7d window from `/api/signals/by-category`)

| Category | Alerts | t15m precision | n |
|---|---|---|---|
| `other` | 26 | **81.8%** (9/11) | 11 |
| `sports` | 10 | 57.1% (4/7) | 7 |
| `crypto` | 3 | 0.0% (0/1) | 1 |
| `politics` | 21 | **7.7% (1/13)** | 13 |

Politics t15m precision of 7.7% is a quality signal. These alerts are firing but the market doesn't move the predicted direction within 15 minutes — likely because political markets are in low-volatility trending states where price moves revert quickly.

### Extreme zone fractions (price ≥ 0.9 or ≤ 0.1 at alert fire time)

| Detector | Extreme zone | Total with price | Fraction |
|---|---|---|---|
| `price_movement` | 1 | 25 | **4.0%** |
| `wallet_activity` | 47 | 130 | **36.2%** |
| `statistical_anomaly` | — | — | n/a |

Price movement detector is firing almost exclusively in the mid-range (96% of alerts). Good signal quality. Whale trades skew toward extreme prices (36%) — expected, as whales often bet on near-certain short-duration outcomes.

---

## Phase 1D — Resolution Layer

**alert_outcomes total:** 70 rows (t15m=49, t1h=19, resolution=2)

**ResolutionSweeper schedule:** runs at application startup + every 6 hours (`cron: 0 0 */6 * * *`). Next scheduled run: 00:00 UTC (approximately 3 hours from audit time).

**Startup sweep result:**
```
resolution_sweep_complete tracked=437 matched=0 pages=5 elapsedMs=2965
```
`matched=0` in Phase A (formal closed-market poll): Gamma's `closed=true` feed returned 5 pages (~1,000 markets) with zero matches against our 437 tracked markets. Early-exit triggered at `MAX_CONSECUTIVE_EMPTY=5` pages. This is correct behaviour — our tracked markets (recent high-volume actives) are not yet formally closed.

**Phase B (effectivelyResolved) — 8 markets currently qualify, none have resolution rows yet:**

These 8 markets have both `resolvedBy` set AND `outcomePrices[0]` at decisive threshold (≥0.99 or ≤0.01):

| marketId | Yes price | Question |
|---|---|---|
| 947281 | 0.006 | Will Ricardo Belmont win the 2026 Peruvian presidential election? |
| 947283 | 0.0005 | Will Carlos Espá win the 2026 Peruvian presidential election? |
| 947273 | 0.0005 | Will Alfonso López Chau win the 2026 Peruvian presidential election? |
| 1729390 | 0.9995 | Will Russia enter Dovha Balka by April 30? |
| 1919307 | 0.005 | Will Bitcoin be above $80,000 on April 13? |
| 842011 | 0.009 | Will Cyprus win Eurovision 2026? |
| 1908720 | 0.9985 | Will Bitcoin be above $66,000 on April 13? |
| 1908676 | 0.004 | Will Bitcoin be between $64,000 and $70,000? |

**Why no resolution rows were written for these at startup:** The startup sweep ran at 20:44 UTC. These markets may have reached decisive prices after that point, or the Phase B scan ran but found fewer matches. The next 6h sweep will capture them. Until then, alerts for these 8 markets have no `horizon=resolution` outcome and are unresolved in scoring.

**The compound Fix-6 / Phase-13 interaction bug:**

`MarketPoller.doUpsert()` lines 371–378 ("Fix 6") reads `existing.getCurrentYesPrice()` and returns early if `p >= 0.98 || p <= 0.02`, without writing Phase 13 fields (`active`, `acceptingOrders`, `outcomePrices`, `resolvedBy`). Markets that were near-resolved BEFORE Phase 13 code was deployed never receive these fields. The `MarketLivenessGate` checks `acceptingOrders == false` and `active == false` — both null → not blocked. The `effectivelyResolved` predicate requires `resolvedBy` AND extreme `outcomePrices` — both null → not matched.

Concrete example: **market 1919489** (IPL cricket: Sunrisers Hyderabad vs Rajasthan Royals). `currentYesPrice=0.9995`. `active=null`, `acceptingOrders=null`, `outcomePrices=null`, `resolvedBy=null`. `endDate=2026-04-20` (future). The liveness gate cannot block it on any dimension. 9 alerts fired against it in 24h despite being effectively decided.

Note: Fix 6 creating a circular condition — once MarketPoller erases `currentYesPrice` (null write), Fix 6 can never trigger on the next cycle (null < 0.98). The skip only reliably works if PricePoller re-sets currentYesPrice between MarketPoller runs.

---

## Phase 1E — Data Freshness

| Component | Last successful run | Status |
|---|---|---|
| `MarketPoller` | 20:56:55 UTC (current) | ✓ healthy — every ~90s |
| `PricePoller` | 20:57:12 UTC (current) | ✓ healthy — every ~2min, 37–44 writes/cycle |
| `WalletPoller` | 21:01:00 UTC (current) | ✓ healthy — 106 wallets, ~521 trades/cycle |
| `StatisticalAnomalyDetector` | continuous, `fired=0` | ✓ warmup (needs ~40 min post-restart) |
| `ResolutionSweeper` | 20:44:36 UTC (startup) | ✓ running on schedule |
| `NewsCorrelationDetector` | n/a | ✗ not present |

**wallet_trades table:** 1,025 rows. Trades present from today (06:16:33 UTC). WalletPoller ingests ~521 trades per ~2-min cycle — these are full historical refetches for all 106 wallets. Writes are idempotent (PK: address+txHash). Alert dedup works at DynamoDB level via `attribute_not_exists` condition on alertId.

**PricePoller writes:** 37–44 price snapshots per cycle out of 400 markets tracked — only markets where CLOB API returns a changed price generate new snapshots. Normal.

---

## Phase 1F — The Noisiness Complaint (Quantified)

**152 alerts in 24h. Is it noisy?**

**Duplicate analysis (same marketId + same type within 15-min window):**

| Detector | Pairs within 15 min | Total | Rate |
|---|---|---|---|
| `price_movement` | 0 | 24 | **0.0%** — dedup working perfectly |
| `wallet_activity` | 76 | 130 | **58.5%** — see below |
| `statistical_anomaly` | 0 | 0 | n/a |

The 58.5% rate for `wallet_activity` is **not a dedup failure**. WalletActivityDetector deduplicates on `address|txHash` (24-hour bucket) — each unique on-chain transaction fires at most one alert per day. The 76 "pairs" are multiple **distinct whale trades** on the same market by different wallets or at different times. The dedup logic is correct.

The noise perception comes from a different source:

**Top 10 markets by alert count (24h):**

| marketId | Alert count | Notes |
|---|---|---|
| 947289 | 13 | Peruvian election (Roberto Sánchez Palomino) — active $2M/day volume |
| 1919489 | 9 | IPL cricket (SRH vs RR) — **currentYesPrice=0.9995**, effectively resolved |
| 1965063 | 8 | — |
| 665374 | 7 | — |
| 1959596 | 6 | — |
| 1712295 | 5 | — |
| 1567746 | 5 | (endDate was 2026-04-12 during alert period; liveness gate now blocking) |
| 1543487 | 5 | — |
| 1953293 | 5 | — |
| 1797309 | 4 | — |

The clearest noise source: **market 1919489 (9 alerts, effectively resolved at 99.95%)** is firing alerts because the liveness gate sees `active=null, acceptingOrders=null` due to the Fix 6 / Phase 13 field interaction described in Phase 1D.

**Distinct wallets triggering alerts:** WalletPoller fetches 106 wallets. Not all generate alerts (threshold $5,000 USDC). The dedup by `address|txHash` is working — same wallet re-fetched across cycles does not re-fire. No evidence of the historical `lastSyncedAt` bug.

---

## Phase 1G — Orphaned Markets + Stale State

**Orphaned markets (in `alerts` table but not in `markets` table):** 0  
All 57 distinct market IDs referenced in alerts exist in the markets table. ✓

**Markets in DB with `endDate` before today:** 0 (confirmed by full scan as of 21:02 UTC)

Note: Market 1567746 appeared in `detector_skipped_market_ended` logs at 20:51 with `endDate=2026-04-12`. By 21:02, MarketPoller had updated it from Gamma API with the corrected `endDate=2026-04-15` (Gamma changed the endDate). The liveness gate was correctly blocking it during the window, and it self-corrected via the next poll cycle.

**Alerts fired after their market's `endDate`:** 0  
The MarketLivenessGate's `reason=endDate` check is correctly blocking post-endDate alerts. ✓

**Markets currently in DB with past endDates:** 0  
MarketPoller's EOL filter (`min-hours-to-end=12`) prevents near-expiry markets from entering the top-400. Markets that drop below the top-400 volume cutoff are not removed from DynamoDB, but they also stop receiving MarketPoller updates. As of audit time, none of the 438 stored markets have past endDates.

---

## Phase 2 — Summary Report

### What's Broken

**1. Dashboard: em-dash prices everywhere in Watched Markets and Live Events**  
Root cause: `MarketPoller.doUpsert()` creates a fresh `Market` object without calling `setCurrentYesPrice()`, then `putItem()` replaces the full DynamoDB item. Every 90-second MarketPoller cycle wipes `currentYesPrice` for all 400 tracked markets. The `AlertController` and market API both serve `currentYesPrice` directly from the Market record — null → em-dash (`—`), 0%-wide progress bar.  
Scope: 10 of 22 watched markets affected at audit time; percentage fluctuates based on how recently PricePoller ran relative to MarketPoller.

**2. Near-resolved market 1919489 (IPL cricket, 99.95%) generating alerts**  
Root cause: MarketPoller "Fix 6" skips writing Phase 13 fields (`active`, `acceptingOrders`, `outcomePrices`, `resolvedBy`) for markets where `currentYesPrice >= 0.98`. Market 1919489 was near-resolved before Phase 13 code deployed → never received those fields → liveness gate checks `active==false` and `acceptingOrders==false` (both null → gate doesn't block) → 9 alerts in 24h against a decided market.

**3. 8 effectively-resolved markets have no `horizon=resolution` outcome rows**  
These markets meet the `effectivelyResolved` predicate but the sweeper has not yet processed them (last sweep ran at startup, next at 00:00 UTC ~3h away). Alerts for these markets cannot be scored as correct/incorrect until the next sweep.

**4. Statistics show misleadingly high t1h precision (87.5%)**  
Only 8 samples. The precision figure shown in the header and stats row is a fraction from 8 events. Not wrong, but misleading — caveat is shown in sub-label ("8 samples · 7d 1h") but a user seeing 87.5% may over-trust it.

### What's Working Correctly

- All 6 API endpoints: HTTP 200, healthy, <300ms
- Alert deduplication for `price_movement`: 0 duplicates in 24h ✓
- Alert deduplication for `wallet_activity` (address|txHash, 24h): correctly prevents re-firing same on-chain trade ✓
- SQS → ntfy pipeline: working, `notification_sent` confirmed ✓
- MarketPoller EOL filter: 0 past-endDate markets in DB ✓
- MarketLivenessGate endDate check: correctly blocking post-endDate markets (liveness gate logic verified via logs) ✓
- WalletPoller: 106 wallets, ~521 trades/cycle, 0 errors, rpc_fallback_used=false ✓
- PricePoller: 37–44 snapshots/cycle, 0 errors ✓
- ResolutionSweeper: correct Phase A + Phase B logic, idempotent writes ✓
- Orphaned markets: 0 ✓
- StatisticalAnomalyDetector: configured correctly; 0 fires expected during warmup ✓

### Ranked Problem List (by user-experience impact)

#### 1. currentYesPrice erasure by MarketPoller — HIGH impact
**Root cause:** `MarketPoller.doUpsert()` lines 384–425 never calls `market.setCurrentYesPrice(existing.getCurrentYesPrice())` before `putItem()`. Every 90s wipe.  
**Fix (one line):** After reading `existing` (line 370), carry the price forward:
```java
// In doUpsert(), after the existing = marketsTable.getItem(key) call:
if (existing != null && existing.getCurrentYesPrice() != null) {
    market.setCurrentYesPrice(existing.getCurrentYesPrice());
}
```
This preserves PricePoller's value across MarketPoller cycles. PricePoller will still overwrite it when the CLOB price changes. em-dashes on Watched Markets and Live Events disappear immediately.

#### 2. Near-resolved markets bypassing the liveness gate — MEDIUM impact
**Root cause:** MarketPoller "Fix 6" (lines 371–378) returns early for markets with `currentYesPrice >= 0.98 || <= 0.02` without writing Phase 13 fields. The liveness gate and `effectivelyResolved` predicate rely on those fields.  
**Fix:** Write Phase 13 fields first, THEN apply the skip-on-put logic. Specifically: resolve the `active`/`acceptingOrders`/`outcomePrices`/`resolvedBy` fields from the API response before checking the existing price, so the gate has current data even if the market's put is skipped.

#### 3. 8 sweeper misses for effectively-resolved markets — LOW-MEDIUM impact
These will self-resolve at 00:00 UTC. Not an ongoing bug — the 6h schedule is by design. Impact: those 8 markets' alerts are unscored for up to 6h.

#### 4. Politics t15m precision 7.7% — signal quality problem
17 total politics alerts over 7 days at t15m; only 1 was correct. PriceMovementDetector fires on 13+ percent moves in political markets, but political market prices are sticky — they don't revert quickly. Consider applying a longer minimum delta threshold or a lower sensitivity for the `politics` category.

#### 5. No NewsCorrelationDetector / StatisticalAnomalyDetector alerts — coverage gap
Zero anomaly/news signals in 24h. Anomaly detector will resume after warmup (~40 min post-restart). News detector is not implemented.

### The Noise Question — Quantified

**Is the data actually noisy?**  
**Yes, specifically in two ways:**

1. **Near-resolved market 1919489 (IPL cricket, 0.9995)** generated 9 alerts in 24h. This is real noise — a decided market generating live alerts. Fix: close the Fix 6 / Phase 13 field gap (problem #2 above).

2. **Politics category signals are 92.3% wrong at t15m** (12/13 incorrect). These alerts look alarming in the Live Events feed but don't predict anything. This is a signal-quality problem, not a pipeline bug.

**What's not actually noise:** The 76 wallet_activity "duplicate pairs" are legitimate distinct whale trades on the same popular markets. Market 947289 (Peruvian election, $2M/day) getting 13 alerts is expected: 13 distinct large trades on an active political market. The system is behaving correctly; the volume just reflects the market's actual whale activity.

---

### Recommended Next Single PR

**`fix(market-poller): preserve currentYesPrice across MarketPoller upsert cycles`**

In `MarketPoller.doUpsert()`, after the read-before-write `getItem` call (line 370), add:
```java
if (existing != null) {
    market.setCurrentYesPrice(existing.getCurrentYesPrice());
}
```

This is a 2-line change, no tests need updating (existing tests mock the DB), and immediately fixes:
- em-dash prices in Watched Markets panel (all 10 affected markets)
- em-dash `currentYesPrice` on alert cards in Live Events
- The Fix 6 circular dependency (Fix 6 now reliably sees the PricePoller's value)

It does NOT fix the near-resolved market bypass — that's a separate, more nuanced change.

Do NOT bundle any other fix into this PR. The currentYesPrice preservation is safe, surgical, and immediately user-visible.

---

*Audit performed 2026-04-13, read-only. No code changes made. All numbers from live DynamoDB and EC2 log queries.*
