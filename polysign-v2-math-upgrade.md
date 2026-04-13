# PolySign v2 Math Upgrade — Handoff Document

**Use this in a fresh Claude conversation to save credits.** Paste the whole file as the first message, then answer Claude's follow-up questions.

---

## Project context (everything new-Claude needs to know)

**What PolySign is:** A Java 25 / Spring Boot 3.5.x prediction market monitoring and alerting platform that integrates with Polymarket. Monitoring-only (zero trading or wallet writes). Built as an Amazon SDE interview portfolio artifact.

**Stack:** Java 25, Spring Boot, DynamoDB Enhanced Client, SQS with DLQs, S3, LocalStack 3.8 (pinned), Resilience4j, Testcontainers, ntfy.sh for push, vanilla HTML dashboard. Deployed on EC2 via Docker Compose.

**Project root:** `/Users/leonardholler/projects/polysign` (NOT `~/polysign` — iCloud has a unicode-apostrophe path collision that requires the full explicit path).

**Deploy path:**
- Mac: `cd /Users/leonardholler/projects/polysign && git add -A && git commit -m "..." && git push`
- EC2: `cd ~/polysign && git pull && docker compose build --no-cache polysign && docker compose up -d polysign`

**What works today (just shipped in Phase 0.5):**
- Crypto alerts get scored via `CategoryClassifier` fallback when DynamoDB lookup returns null.
- Dashboard header shows real aggregate signal precision (51.6% → currently ~50.4% at 1h with 383 samples).
- Extreme-zone cutoffs (0.05 / 0.95) tunable via `polysign.detectors.common.extreme-zone-low` / `-high` in `application.yml`, bound to `CommonDetectorProperties`.
- Detectors: `PriceMovementDetector`, `WalletActivityDetector`, `StatisticalAnomalyDetector`. All three use `CommonDetectorProperties`.

**What's weak (why we're doing v2):**
- Overall precision ~50% — coinflip. Sports at 67% (1h) is the only real edge. Politics inverted (25-31%, confirmed as real market efficiency per audit, not a bug). Anomaly detector firing at 0% precision.
- Detectors use fixed thresholds — same 2% minimum move for a stable election market as a volatile 15-min crypto market. Produces noise.

**Known state from prior audit (`/tmp/polysign-audit.md` on Mac):**
- No existing `RollingStatistics`, `Welford`, or rolling-window utility anywhere in the codebase.
- No existing logit / log-odds transformations in detector code.
- No existing confluence / cross-detector event wiring. No `ApplicationEventPublisher` usage in `AlertService`.
- `AlertService` creates alerts via a method that does conditional DynamoDB writes for idempotency.
- Existing `FILTERED_*` enum values per detector already include `FILTERED_EXTREME_ZONE`, `FILTERED_MIN_DELTA_P`, plus tier-specific ones.
- `CommonDetectorProperties` is the only current cross-detector config class.
- `DiagnosticsEndpoint` lives at `/actuator/diagnostics/*` with endpoints for `resolution-coverage` and `detector-thresholds`.
- `DESIGN.md` and `README.md` already exist and must be UPDATED in place, not overwritten.

**The goal of v2:** move from fixed-threshold detectors to adaptive statistical detectors using per-market rolling z-scores on logit-transformed prices, volume percentile gating, smart-money-weighted wallet confluence, and cross-detector confluence scoring. Target: lift aggregate precision from 50% to 60%+.

---

## Baseline (fill in before starting)

Paste these values from your pre-flight checks:

```
Audit file status: [EXISTS / MISSING]
Current /api/stats baseline:
  signalPrecision7d1h:  ____
  signalPrecision7d15m: ____
  scoredSamples7d1h:    ____
  scoredSamples7d15m:   ____
  alertsFiredToday:     ____
```

**If audit file is MISSING, regenerate sections 1-9 before Phase 1** using the regeneration prompt at the end of this doc.

---

## Execution plan

Four phases. Deploy, verify, and check precision between each. Expected total: 4-7 hours active work + 6-12 hours passive waiting to see if precision improved.

---

### PHASE 1 — RollingStatistics utility (30-45 min)

**Goal:** Build and test the pure math class in isolation. No Spring, no DynamoDB, no integration.

**Prompt to paste into Claude Code:**

```
In /Users/leonardholler/projects/polysign, create ONE new file and ONE
test file. Do not modify any existing code.

FILE 1: src/main/java/com/polysign/stats/RollingStatistics.java

Requirements:
- Fixed-capacity ring buffer of double values. Constructor takes int
  capacity > 0.
- push(double value): O(1) insert, evicts oldest when full.
- count(): current sample count (0 to capacity).
- mean(), variance(), stddev(): recompute from buffer contents on each
  call (O(n) per query, O(1) per push). Capacity expected <= 200 so this
  is acceptable. Document the tradeoff in javadoc.
- Sample variance: divide by (n-1) when n >= 2, return 0 when n < 2.
- zScore(double observation): (observation - mean()) / stddev(). Return
  0 if stddev is 0 or count < 2.
- percentile(double p): p in [0, 1]. Array copy + sort + linear
  interpolation between nearest ranks. Return NaN if count == 0.
- Thread-safe via a single ReentrantLock held for all operations.

FILE 2: src/test/java/com/polysign/stats/RollingStatisticsTest.java

JUnit 5 tests:

1. testBasicMeanAndVariance — push 1,2,3,4,5. Assert mean=3.0 ±1e-9,
   variance=2.5 ±1e-9.
2. testGaussianSamples — seeded Random(42), push 1000 N(0,1) samples.
   Assert |mean|<0.1, |stddev-1.0|<0.1.
3. testOutlierZScore — 100 N(0,1) samples then push 10.0. Assert
   zScore(10.0) in [8.0, 12.0].
4. testCapacityEviction — capacity=10, push 15 values. Assert
   count()==10 and first 5 values no longer reflected in mean.
5. testPercentile — push 1..100. Assert percentile(0.5) in [50,51],
   percentile(0.75) in [75,76], percentile(0.0)==1, percentile(1.0)==100.
6. testEmptyAndSingleSample — empty: count=0, mean=0, variance=0,
   zScore=0. After one push: count=1, variance=0, zScore=0.
7. testThreadSafety — 4 threads push 10000 random doubles each. Assert
   no exceptions, final count()==capacity.

Run `mvn -q -Dtest=RollingStatisticsTest test` and show output. All 7
tests must pass. Do not declare done until green.
```

**Verification after Claude Code reports done:**
```bash
cd /Users/leonardholler/projects/polysign
mvn -q -Dtest=RollingStatisticsTest test
```
Should say `Tests run: 7, Failures: 0, Errors: 0`.

**Commit and deploy:**
```bash
git add -A && git commit -m "feat(stats): add RollingStatistics utility for v2 detectors" && git push
```
(No EC2 deploy needed yet — nothing uses it in production.)

**Success criteria:** All 7 tests pass locally. Nothing deployed to EC2 yet.

---

### PHASE 2 — Detectors with z-score + logit + category overrides (90-120 min)

**Goal:** Wire `RollingStatistics` into `PriceMovementDetector` and `WalletActivityDetector`. Add category-specific threshold overrides for politics/crypto/sports.

**Prompt to paste into Claude Code:**

```
In /Users/leonardholler/projects/polysign, upgrade PriceMovementDetector
and WalletActivityDetector to use RollingStatistics (already built and
tested — do not modify it). Also update application.yml and add
category-specific threshold overrides.

PRE-FLIGHT: read /tmp/polysign-audit.md if present, especially sections
on existing enums (section 5) and config (section 6). Do NOT duplicate
anything existing; extend it.

========================================================================
CHANGE 1: PriceMovementDetector (additive, runs alongside existing logic)
========================================================================

Add:
- ConcurrentHashMap<String, RollingStatistics> deltaLogitWindows
- ConcurrentHashMap<String, RollingStatistics> volumeWindows
- ConcurrentHashMap<String, Instant> lastSeenPerMarket (for cleanup)
- Static helper:
    private static double logit(double p) {
      double c = Math.max(0.001, Math.min(0.999, p));
      return Math.log(c / (1.0 - c));
    }

Append new filter enum values (do not remove/rename existing):
  FILTERED_INSUFFICIENT_HISTORY
  FILTERED_ZLOGIT_BELOW_THRESHOLD
  FILTERED_VOLUME_BELOW_PERCENTILE
  FILTERED_ABS_DELTA_LOGIT_BELOW_FLOOR

New detection flow (in addition to existing path; both can fire;
AlertService idempotency dedups):

  1. deltaLogit = logit(priceNow) - logit(pricePrev)
  2. deltaLogitWindow.push(deltaLogit); volumeWindow.push(volumeInWindow)
  3. If window.count() < minSamples: FILTERED_INSUFFICIENT_HISTORY; log; return
  4. zLogit = window.zScore(deltaLogit)
  5. Resolve category from market; look up category override (below)
  6. If |deltaLogit| < minAbsDeltaLogit: FILTERED_ABS_DELTA_LOGIT_BELOW_FLOOR
  7. If |zLogit| < effectiveZLogitThreshold:
     FILTERED_ZLOGIT_BELOW_THRESHOLD
  8. volumeP = volumeWindow.percentile(volumePercentile)
  9. If volumeInWindow < volumeP: FILTERED_VOLUME_BELOW_PERCENTILE
  10. Fire alert. signalStrength:
        |zLogit| >= 5.0 → 4
        |zLogit| >= 4.0 → 3
        |zLogit| >= 3.0 → 2
      Metadata: zLogit, deltaLogit, volumeP, sampleCount, category,
      effectiveThreshold.

@Scheduled(fixedRate=3600000) cleanupStaleMarkets() evicts window entries
not observed in >24h.

Structured log on every pass (INFO for first 10 min after boot, DEBUG
after — mirror the bootTime pattern from WalletActivityDetector):
  "price_detection_v2 market={} category={} price_prev={} price_now={}
   delta_logit={} z_logit={} volume={} volume_p={} samples={}
   effective_threshold={} decision={} signal_strength={}"

========================================================================
CHANGE 2: WalletActivityDetector (additive)
========================================================================

Do NOT remove per-trade threshold logic. Add a parallel confluence path.

Add:
- ConcurrentHashMap<WalletMarketKey, TimeBucketedNetFlow>
  where WalletMarketKey is record(String address, String marketId)
  and TimeBucketedNetFlow tracks rolling (timestamp, signedUsdc) tuples
  pruned on update, holding bucketMinutes (default 15) of history.
- ConcurrentHashMap<String, RollingStatistics> marketFlowWindows
- Caffeine cache or equivalent for wallet qualityScore (5-min TTL).
  Do NOT read DynamoDB on every trade.

New detection flow per trade:
  1. netFlow over last bucketMinutes for (wallet, market)
  2. Lookup qualityScore from cache
  3. smartWeight = min(qualityScore / weightDivisor, weightCap)
  4. signal = |netFlow| * smartWeight
  5. marketFlowWindows[marketId].push(signal)
  6. If count < minSamples: skip confluence path; FILTERED_INSUFFICIENT_HISTORY
  7. z = window.zScore(signal)
  8. Resolve category; apply category override
  9. Fire if: z >= effectiveZScoreThreshold AND |netFlow| >= minNetFlowUsdc
     AND smartWeight >= 0.6
  10. Second-level dedup: alert SK includes
      CONFLUENCE#{bucketStart15min}#{marketId}

Structured log:
  "wallet_confluence wallet={} market={} category={} net_flow_usdc={}
   quality_score={} smart_weight={} signal={} z_score={} samples={}
   effective_threshold={} decision={}"

========================================================================
CHANGE 3: Category-aware thresholds
========================================================================

In application.yml under polysign.detectors:

  price:
    z-logit-threshold: 3.0
    volume-percentile: 0.75
    min-abs-delta-logit: 0.15
    rolling-window-size: 60
    min-samples: 30
    category-overrides:
      politics:
        z-logit-threshold: 4.0
        min-abs-delta-logit: 0.25
      crypto:
        z-logit-threshold: 3.5
      sports:
        z-logit-threshold: 3.0
  wallet:
    z-score-threshold: 3.0
    min-net-flow-usdc: 5000
    quality-score-weight-divisor: 5.0
    quality-score-weight-cap: 2.0
    bucket-minutes: 15
    min-samples: 20
    category-overrides:
      politics:
        z-score-threshold: 4.0
        min-net-flow-usdc: 10000
      crypto:
        z-score-threshold: 3.5

Create @ConfigurationProperties classes PriceDetectorV2Properties and
WalletConfluenceProperties. Each exposes:
  double baseThreshold()
  double effectiveThreshold(String category)  // falls back to base

========================================================================
DELIVERABLES
========================================================================

1. Modified PriceMovementDetector.java
2. Modified WalletActivityDetector.java
3. New PriceDetectorV2Properties.java
4. New WalletConfluenceProperties.java
5. Modified application.yml
6. Update existing detector unit tests to pass the new config objects;
   add one new test per detector covering the happy-path v2 flow with
   enough pushed samples to clear MIN_SAMPLES.
7. mvn -q test must pass. Show output.

DO NOT:
- Modify AlertService, SQS, notification consumers, DynamoDB schemas,
  controllers.
- Touch StatisticalAnomalyDetector.
- Remove any existing filter enum values.
- Change ntfy push behavior.
```

**Verification after Claude Code reports done:**
```bash
cd /Users/leonardholler/projects/polysign
mvn -q test
```
All tests pass.

**Commit and deploy:**
```bash
git add -A && git commit -m "feat(detectors): v2 adaptive statistical detection with z-score + logit + category overrides" && git push
```
On EC2:
```bash
cd ~/polysign && git pull && docker compose build --no-cache polysign && docker compose up -d polysign
```

**Verify on EC2 after ~2 minutes:**
```bash
docker compose logs polysign --since 5m | grep -iE "price_detector|wallet_confluence|v2"
```
Should see boot config logs confirming new thresholds AND (after ~15 min of traffic) `price_detection_v2` and `wallet_confluence` log lines flowing.

**IMPORTANT — passive wait:** The system needs 30 min to 1 hour to populate rolling windows past MIN_SAMPLES. Precision won't move immediately. Wait **6-12 hours** before judging whether Phase 2 worked.

**Decision point:** After 6-12 hours, hit `/api/stats` on EC2:
```bash
curl -s http://localhost:8080/api/stats
```
Compare `signalPrecision7d1h` to baseline.
- **Improved (>52%):** good, proceed to Phase 3.
- **Same (~50%):** thresholds need tuning. Edit `application.yml` values (raise z-thresholds to 3.5 or 4.0), redeploy, wait another 6 hours.
- **Worse (<48%):** something misfired. Check logs for exception stack traces. May need to revert.

---

### PHASE 3 — Confluence scorer (60-90 min)

**Goal:** Fire a high-confidence alert when ≥2 detector types fire for the same market within 10 minutes.

**Prompt to paste into Claude Code:**

```
In /Users/leonardholler/projects/polysign, add a cross-detector
confluence engine. Pre-read /tmp/polysign-audit.md sections 3 (event
wiring) and 4 (AlertService). If any cross-detector event bus exists,
reuse; do not create a new one.

========================================================================
FILE 1: src/main/java/com/polysign/alert/AlertCreatedEvent.java
========================================================================

Java record:
  String alertId
  String marketId
  String detectorType  // "price_movement" | "wallet_activity" |
                       // "statistical_anomaly" | "confluence"
  int signalStrength
  String category
  Instant createdAt

========================================================================
FILE 2: Modify AlertService
========================================================================

Inject ApplicationEventPublisher via constructor. After a SUCCESSFUL
alert write (not on dedup-skip), call:
  applicationEventPublisher.publishEvent(new AlertCreatedEvent(...));

Do not change any existing return type or method signature.

========================================================================
FILE 3: src/main/java/com/polysign/detector/ConfluenceScorer.java
========================================================================

@Component with @EventListener on AlertCreatedEvent.

State:
  ConcurrentHashMap<String, Deque<AlertCreatedEvent>> recentByMarket

On each event:
  1. Ignore if detectorType == "confluence" (no recursion).
  2. Append to deque for marketId. Evict entries older than
     windowMinutes (default 10).
  3. Count distinct detectorTypes in the deque.
  4. If distinct >= minDistinctTypes (default 2) AND no confluence alert
     fired for this marketId in last windowMinutes:
     a. totalStrength = min(sum of component strengths, 5)
     b. severity = totalStrength >= highSeverityStrength ? "high"
                                                         : "medium"
     c. Create confluence alert via AlertService. Metadata includes
        contributing_alert_ids list and component detector types.
     d. Dedup SK: CONFLUENCE_META#{marketId}#{windowStartRoundedTo10Min}.

@Scheduled fixedRate=5min cleanup prunes empty deques.

========================================================================
FILE 4: application.yml
========================================================================

Append:
  polysign:
    detectors:
      confluence:
        enabled: true
        window-minutes: 10
        min-distinct-types: 2
        high-severity-strength: 5

========================================================================
TESTS (ConfluenceScorerTest)
========================================================================

1. Fire price_movement alert for market M → no confluence.
2. Fire wallet_activity for M within 10 min → confluence fires,
   totalStrength = sum, contributing_alert_ids has both.
3. Fire 3rd alert within same window → no new confluence (dedup).
4. Fire 3rd alert 11 min later → new confluence fires.
5. Fire a confluence alert → does NOT trigger recursive confluence.

mvn -q test must pass. Show output.

DO NOT:
- Change ntfy push behavior. Confluence alerts reach ntfy via the
  existing SQS pipeline.
- Modify detector code.
- Touch DynamoDB schemas.
```

**Verification:**
```bash
cd /Users/leonardholler/projects/polysign && mvn -q test
```

**Commit and deploy:**
```bash
git add -A && git commit -m "feat(confluence): cross-detector confluence scorer with 10-min window" && git push
```
EC2:
```bash
cd ~/polysign && git pull && docker compose build --no-cache polysign && docker compose up -d polysign
```

**Verify on EC2:**
```bash
docker compose logs polysign --since 5m | grep -iE "confluence|AlertCreatedEvent"
```
Should see boot logs for ConfluenceScorer. Wait 30-60 min for first confluence alerts to appear.

**Check for confluence alerts in the dashboard or via:**
```bash
curl -s "http://localhost:8080/api/alerts?type=confluence" | head -c 500
```

---

### PHASE 4 — Diagnostics + update DESIGN.md and README.md (45-60 min)

**Goal:** Expose tuning observability and document the v2 work for the interview.

**Prompt to paste into Claude Code:**

```
Final phase. Two parts.

========================================================================
PART 1: Extend DiagnosticsEndpoint
========================================================================

Read src/main/java/com/polysign/api/DiagnosticsEndpoint.java.

Add new @GetMapping endpoints. Do NOT modify existing ones.

/actuator/diagnostics/statistical-detectors returns JSON:
{
  "price": {
    "config": { resolved PriceDetectorV2Properties including category
                overrides },
    "markets_with_sufficient_history": N,
    "total_markets_tracked": M,
    "recent_z_scores_sample": [...]   // reservoir sample size 5
  },
  "wallet": { same shape },
  "confluence": {
    "config": { resolved ConfluenceProperties },
    "confluence_alerts_last_1h": N,
    "pairs_breakdown": [
      { "types": ["price_movement","wallet_activity"], "count": 7 },
      ...
    ]
  }
}

Implement a reservoir sampler (Algorithm R) of size 5 inside each of the
two v2 detectors. Expose via package-private getter.

========================================================================
PART 2: Update DESIGN.md and README.md (do NOT overwrite)
========================================================================

Read existing DESIGN.md and README.md in full. Do NOT replace them.

For DESIGN.md:
- Find the detector-logic section (check ToC).
- Add new subsection "Adaptive Statistical Detection (v2)" covering:
  * why fixed thresholds were insufficient (cite observed precision
    around 50% in the v1 system)
  * logit transformation — why it matters for tail-zone markets
  * per-market rolling z-score approach
  * volume-percentile gating
  * smart-money-weighted wallet confluence via qualityScore
  * cross-detector confluence scoring
  * MIN_SAMPLES fallback for cold-start
  * category-specific thresholds for politics/crypto/sports
  * observability via /actuator/diagnostics/statistical-detectors
- Under 600 words. Equations in plain text, not LaTeX.
- Do NOT touch any other section.

For README.md:
- Find "How it works" or "Architecture" section.
- Add one paragraph summarizing v2 detection, linking to DESIGN.md
  section.
- Update any hardcoded threshold values mentioned to reference config
  instead.
- Do NOT touch setup/install/deployment sections.

Show a diff of both files. Do not commit yet; human reviews first.

========================================================================
DELIVERABLES
========================================================================

1. Modified DiagnosticsEndpoint.java
2. Two reservoir-sampler additions (one per v2 detector)
3. DESIGN.md diff for review
4. README.md diff for review
5. mvn -q test must pass.
```

**Verification:**
```bash
mvn -q test
curl -s http://localhost:8080/actuator/diagnostics/statistical-detectors | head -c 500
```

**After reviewing the DESIGN.md / README.md diffs, commit:**
```bash
git add -A && git commit -m "docs+diagnostics: v2 detection observability + DESIGN.md update" && git push
```
Deploy to EC2 same as before.

---

## Final verification (the "before and after" moment)

Wait 12-24 hours after Phase 3 deploy. Then:

```bash
curl -s http://localhost:8080/api/stats
curl -s "http://localhost:8080/api/signals/performance?horizon=t1h"
curl -s http://localhost:8080/actuator/diagnostics/statistical-detectors
```

Compare `signalPrecision7d1h` to the baseline at the top of this doc. That's the number that goes in DESIGN.md as your result.

Also check per-category precision from `/api/signals/by-category`:
- Sports should stay high.
- Politics/crypto should no longer be worse-than-random (category overrides did their job).
- A new "confluence" detector type should appear in `/api/signals/performance` with the HIGHEST precision of all.

---

## Rollback plan if anything goes sideways

Each phase is one commit. To revert:

```bash
cd /Users/leonardholler/projects/polysign
git log --oneline | head -10   # find the bad commit
git revert <commit-sha>
git push
cd ~/polysign  # on EC2
git pull && docker compose build --no-cache polysign && docker compose up -d polysign
```

Phases 1-4 are additive — reverting Phase 2 or 3 leaves you on the v1 detectors which still work.

---

## If /tmp/polysign-audit.md is missing — regenerate it first

Paste this to Claude Code before Phase 1:

```
In /Users/leonardholler/projects/polysign, perform a READ-ONLY audit.
Write /tmp/polysign-audit.md covering:

1. Any existing rolling-window / moving-average / z-score / Welford /
   percentile code (search all of src/main). Report class names,
   packages, usage sites.
2. Any existing logit / log-odds / Math.log in detector code.
3. Any existing confluence / cross-detector coordination. Check for
   @EventListener, ApplicationEventPublisher, classes named
   *Confluence*, *Correlator*, *Aggregator*, *Scorer*.
4. AlertService surface: method signatures for creating alerts, whether
   it publishes Spring events, Alert model fields (especially
   signal_strength, metadata, severity), dedup mechanism.
5. All FILTERED_* / decision enum values per detector
   (PriceMovementDetector, WalletActivityDetector,
   StatisticalAnomalyDetector).
6. Current polysign.detectors.* config in application.yml, plus all
   @ConfigurationProperties classes bound to it (especially
   CommonDetectorProperties).
7. DiagnosticsEndpoint current endpoints and data sources.
8. Test count per detector package.
9. DESIGN.md and README.md H1/H2/H3 headers (so we know what sections
   to update, not create).
10. Any planned v2 change that would conflict with existing code.
    Flag partial z-score logic, existing per-market state maps,
    pre-existing event buses, @Scheduled cleanup methods.

Read-only. Do not modify any code. Report under 600 lines, with file
paths and line numbers for anything referenced.
```

---

## Summary for the new Claude

Paste above this line into your next conversation. Then tell the new Claude:

1. "My baseline signalPrecision7d1h is ____ with ____ scored samples."
2. "Audit file is [present/missing]."
3. "Start with [Phase 1 / audit regeneration, depending on #2]."

The new Claude should not need additional context beyond this document.
