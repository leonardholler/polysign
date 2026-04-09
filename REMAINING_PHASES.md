# PolySign — Remaining Phases Roadmap (v2)

Your complete guide from Phase 5 through Phase 12. Save this whole file as `~/polysign/REMAINING_PHASES.md` so you always have it.

## How to start every session (the ritual)

1. Open a **fresh** Claude Code session in `~/polysign`. Never reuse a stale one.
2. First message: `/model sonnet` (or `/model opus` for Phases 5 and 11 only).
3. Second message: the **standard kickoff** (below).
4. Third message: the **phase prompt** (below).
5. When the phase is done, `/exit` and run `git log --oneline` from your terminal to confirm commits landed.

**Critical**: every session, use `/Users/leonardholler/polysign` as the project path. If Claude Code ever says it can't find files, respond: *"The project is at /Users/leonardholler/polysign. Use that exact path."*

---

## Standard kickoff (Message 2, every single session)

Copy-paste this exact block as message 2 every time:

```
Read these three files in full before doing anything else:
1. /Users/leonardholler/polysign/spec.md
2. /Users/leonardholler/polysign/CONVENTIONS.md
3. /Users/leonardholler/polysign/PROGRESS.md

Confirm you have read them by telling me in one sentence:
- what phase we are on (based on PROGRESS.md)
- what the most important convention from CONVENTIONS.md is
- any blockers noted in PROGRESS.md

Then wait for my next message.
```

When Claude Code replies, verify it correctly identifies the current phase before sending the phase prompt. If it says the wrong phase, correct it in one sentence before proceeding.

---

# Phase 5 — Statistical Anomaly Detector + Orderbook Depth (OPUS, strict TDD)

**Model**: `/model opus`
**Budget**: $5–13
**Do not start this phase with less than $13 remaining.**

**Phase prompt (Message 3):**

```
We are on Phase 5: Statistical Anomaly Detector + Orderbook Depth Capture.

Strict TDD for this phase. Tests before implementation. Do NOT write any
detector code until I approve the test file.

CHECKPOINT 1 — Synthetic test series

Write StatisticalAnomalyDetectorTest.java FIRST with these cases, using
synthetic price snapshots:
  1. Flat series (price constant) → no alert
  2. Linear trend (slow drift) → no alert
  3. Random walk with small noise → no alert
  4. Sudden 3.5σ spike on otherwise flat series → alert
     (use small ε-noise on the base so stddev is nonzero — do NOT rely on
      division-by-zero edge case behavior)
  5. Sudden 5σ spike → alert
     (same ε-noise base as #4)
  6. Gradual acceleration (returns ramp 0.001, 0.002, ..., 0.024) → no alert
     (must be a smooth ramp, not a step — stddev should absorb the ramp)
  7. Window with <20 snapshots → no alert (insufficient history guard)
  8. High-volatility market where a 10% move is NOT anomalous (prior 60min
     has ±22% alternating swings so stddev absorbs a 10% last move) → no alert
  9. Low-volume market (<$50k) with a real anomaly → no alert (volume floor)

Show me the tests. Explain each synthetic series in a brief comment above it.
WAIT for my approval before writing any implementation code.

CHECKPOINT 2 — Implementation

- Build StatisticalAnomalyDetector.java: rolling z-score of 1-minute returns
  over last 60min
- Use AlertService from Phase 3 (deterministic ID, idempotent) — same
  infrastructure, different algorithm
- Alert metadata includes z-score, window size, mean, stddev
- Apply the same delta-p floor (0.03) and extreme-zone filter (0.05/0.95)
  from Phase 4.5 — the z-score detector must not fire on tail-zone noise
- Handle stddev=0 sanely: if stddev is zero, skip the market (no
  information about volatility yet)

CHECKPOINT 3 — Orderbook depth capture

Add orderbook depth capture to BOTH PriceMovementDetector and
StatisticalAnomalyDetector. At the moment the detector decides to fire an
alert (not on every poll):

- Call clob.polymarket.com/book?token_id={yesTokenId} once through the
  same Resilience4j-wrapped WebClient used by PricePoller
- Compute:
    spreadBps = (bestAsk - bestBid) / midpoint * 10000
    depthAtMid = sum(size * price for bid levels within 1% of midpoint)
               + sum(size * price for ask levels within 1% of midpoint)
- Attach spreadBps and depthAtMid to alert metadata
- 500ms timeout budget for the CLOB call
- If the call fails or times out, fire the alert anyway with both fields null

Add unit tests:
  - Synthetic book with known depth → expected spreadBps and depthAtMid values
  - CLOB call fails → alert still fires with null book fields
  - CLOB call times out → alert still fires with null book fields

Run all tests. All must pass. Wire StatisticalAnomalyDetector into the
scheduler after PriceMovementDetector.

Verification: run the app for 5 minutes against real Polymarket data.
Confirm:
  1. No spam alerts on low-liquidity markets
  2. At least one alert has non-null spreadBps and depthAtMid in its metadata
  3. If CLOB is slow, alerts still fire (book fields null)

If spam occurs, tune the volume floor and document in PROGRESS.md.

Update PROGRESS.md. STOP. Do not start Phase 6.
```

**Watch for**: Between Checkpoint 1 and Checkpoint 2, actually read the test file before approving. Cases #6 (gradual acceleration) and #8 (high-vol market) are the most likely to be subtly wrong. Also make sure tests 4 and 5 have nonzero base stddev so the implementation doesn't need to special-case division by zero.

---

# Phase 6 — Wallet Tracking + Consensus Detector (Sonnet)

**Model**: `/model sonnet`
**Budget**: $2–5

**Phase prompt (Message 3):**

```
We are on Phase 6: Wallet Tracking + Consensus Detector.

Before writing code:
1. Make ONE live curl to https://data-api.polymarket.com/trades?user=<any
   placeholder address> and show me the actual response shape.
2. Same for /positions?user=<address>.
3. If either endpoint doesn't work (PROGRESS.md notes CLOB /trades returns
   401 — data-api may too), propose a Polygon RPC fallback plan.
4. Wait for "go".

Scope:
- WalletPoller.java: for each watched wallet, fetch trades since
  lastSyncedAt, write to wallet_trades (idempotent on txHash)
- WalletActivityDetector.java: fire `info` alert on individual trades > $5,000
- ConsensusDetector.java: after each trade write, query
  marketId-timestamp-index GSI — if 3 distinct watched wallets traded same
  market same direction within last 30min, fire `critical` consensus alert
- Load watched_wallets.json at startup (idempotent bootstrap per spec)

Unit tests for consensus logic with fake trades:
  1. 2 wallets same direction → no alert
  2. 3 wallets same direction within 30min → alert
  3. 3 wallets mixed directions → no alert
  4. 3 wallets same direction but spanning >30min → no alert
  5. 3 wallets same direction but only 2 are distinct addresses → no alert
  6. 4 wallets same direction → one alert (not multiple, idempotency)

Verification: seed fake trades via direct awslocal dynamodb put-item, run
detector, confirm consensus alert fires exactly once.

Update PROGRESS.md. STOP. Do not start Phase 7.
```

**Watch for**: If both `/trades` endpoints return 401, the Polygon RPC fallback is more complex. Review Claude Code's fallback plan carefully before saying "go".

---

# Phase 7 — News Ingestion + Correlation (Sonnet)

**Model**: `/model sonnet`
**Budget**: $2–4

**Phase prompt (Message 3):**

```
We are on Phase 7: News Ingestion + Correlation.

Scope:
- RssPoller.java using Rome, polling the RSS feeds from application.yml
  every 5min
- Article dedupe via SHA-256(url) as articleId
- Raw HTML archived to S3 at articles/yyyy/MM/dd/articleId.html
- Article metadata written to `articles` table
- New articles enqueued to news-to-process SQS
- KeywordExtractor.java: simple stopword-filtered token extraction with
  unit tests
- NewsMatcher.java: score article against active markets by keyword overlap
  with unit tests
- NewsConsumer.java: SQS listener on news-to-process
- NewsCorrelationDetector.java: fire `warning` alert if score ≥ 0.5 AND
  market volume ≥ $100k

Plan in 5 bullets, then "go".

Verification: run for 10min, confirm articles land in DynamoDB and S3,
confirm at least one market_news_match row exists.

Update PROGRESS.md. STOP. Do not start Phase 7.5.
```

---

# Phase 7.5 — Signal Quality Infrastructure (Sonnet, strict TDD) ⭐ NEW

**Model**: `/model sonnet`
**Budget**: $4–7
**This is the single most important phase for project credibility.**

**Phase prompt (Message 3):**

```
We are on Phase 7.5: Signal Quality Infrastructure. This is the most
important phase for project credibility. Strict TDD — tests first, then
implementation.

Read the "Signal Quality & Backtesting" section of spec.md in full before
doing anything else. Confirm to me in one sentence what "precision" means
in this project and what the 0.5pp dead zone is for. Then wait for "go".

CHECKPOINT 1 — Schema and archiver

1. Add AlertOutcome model bean with PK=alertId, SK=horizon. Attributes per
   spec.md: type, marketId, firedAt, evaluatedAt, priceAtAlert,
   priceAtHorizon, directionPredicted, directionRealized, wasCorrect,
   magnitudePp, spreadBpsAtAlert, depthAtMidAtAlert. GSI
   type-firedAt-index on (type, firedAt).
2. Add alert_outcomes table creation to BootstrapRunner (idempotent, with
   GSI).
3. Add DynamoDbTable<AlertOutcome> bean to DynamoConfig.
4. Extend price_snapshots.expiresAt from 7 days to 30 days in BootstrapRunner
   and in the PriceSnapshot writer. Note in PROGRESS.md that existing
   snapshots will die at their original TTL; the new TTL applies only to
   snapshots written after this phase.
5. Write SnapshotArchiver.java:
   - @Scheduled daily at 04:00 UTC
   - Reads last 24h per tracked market
   - Writes snapshots/yyyy/MM/dd/{marketId}.jsonl.gz to
     s3://polysign-archives
   - Idempotent on S3 key
   - Emits polysign.archive.snapshots.written counter
6. Unit tests for SnapshotArchiver:
   - Empty day (no markets with snapshots) → no S3 writes
   - Multi-market day → one S3 key per market, correct format
   - S3 key format assertion (matches snapshots/yyyy/MM/dd/marketId.jsonl.gz)

Show me the schema, the archiver, and the tests. Do not start CHECKPOINT 2
until I approve.

CHECKPOINT 2 — Outcome evaluator

1. Write AlertOutcomeEvaluator.java:
   - @Scheduled every 5 minutes
   - Query alerts created between 15min ago and 25h ago via the
     marketId-createdAt GSI (or a targeted scan)
   - For each alert, for each horizon (t15m, t1h, t24h):
     - Check if outcome row already exists for this (alertId, horizon) —
       skip if so (the attribute_not_exists conditional write also
       enforces this, but the pre-check avoids unnecessary DynamoDB calls)
     - Fetch the price snapshot closest to firedAt + horizon from
       price_snapshots (within a ±2min tolerance window)
     - If no snapshot found within tolerance, skip this horizon (will be
       retried next cycle)
     - Compute:
         magnitudePp = (priceAtHorizon - priceAtAlert) in the predicted
                       direction (negative if direction mismatched)
         directionRealized = "up" if (priceAtHorizon - priceAtAlert) > 0.005
                           = "down" if < -0.005
                           = "flat" otherwise (0.5pp dead zone)
         wasCorrect = (directionRealized == directionPredicted)
                    = null if directionRealized == "flat"
     - Write with attribute_not_exists(horizon) for idempotency
   - Emit polysign.outcomes.evaluated counter per (type, horizon)

2. Write a separate ResolutionSweeper.java:
   - @Scheduled every 6 hours
   - Scans markets table for rows with closed=true (or equivalent
     indicator from the Gamma API response)
   - For each closed market, queries alerts via marketId-createdAt GSI to
     find every alert ever fired on it
   - Computes the `resolution` outcome row using the final outcome price
     (0 or 1) as priceAtHorizon
   - Same idempotent conditional write

3. Unit tests (MANDATORY, do not skip any):
   - Correct-direction hit: up-alert at 0.50, snapshot at +1h is 0.55 →
     wasCorrect=true, magnitudePp=+0.05
   - Wrong-direction hit: up-alert at 0.50, snapshot at +1h is 0.45 →
     wasCorrect=false, magnitudePp=-0.05
   - Dead-zone flat: up-alert at 0.50, snapshot at +1h is 0.503 →
     directionRealized=flat, wasCorrect=null (excluded from precision)
   - Missing snapshot: no snapshot within tolerance → horizon skipped,
     no row written
   - Idempotent re-run: same alert + horizon twice → one row only
   - Resolution correctness: up-alert on YES-resolved market (outcome=1)
     → wasCorrect=true; down-alert on same market → wasCorrect=false

4. Wire both schedulers in.

Do not start CHECKPOINT 3 until all tests pass and I approve.

CHECKPOINT 3 — Performance API

1. SignalPerformanceService.java:
   - Takes (type filter, horizon, since) parameters
   - Scans alert_outcomes via type-firedAt-index GSI filtered by firedAt >= since
   - If type filter is present, filters to that type only
   - Computes per-detector aggregates:
       count
       precision = correctCount / (correctCount + wrongCount)
                 — flat outcomes excluded from denominator
       avgMagnitudePp
       medianMagnitudePp
       meanAbsMagnitudePp
   - Returns a plain record / list of records

2. SignalPerformanceController:
   - GET /api/signals/performance
   - Query params with Bean Validation:
       type (optional, String, one of the 4 known detector types)
       horizon (optional, default "t1h", one of t15m|t1h|t24h|resolution)
       since (optional ISO-8601, default 7 days ago)
   - Response shape matches spec.md

3. Unit test for the service: seed 20 fake outcomes across 3 detector
   types at t1h, assert the precision numbers match hand-computed values.

4. Integration test (MockMvc or full context):
   - 200 on valid query
   - 400 on invalid horizon
   - Response shape matches spec

Verification:
- mvn test → all green
- docker compose up -d --build
- Wait 10 minutes (or seed some historical alerts manually if live alerts
  are slow)
- Confirm alert_outcomes has rows for t15m horizon on alerts fired >15min
  ago
- curl localhost:8080/api/signals/performance?horizon=t15m → valid JSON
  with at least one detector type

Update PROGRESS.md. STOP. Do not start Phase 8.
```

**Watch for**: Scope creep into embedding-based attribution, IsolationForest, or fancy statistics. Keep it boring: count + precision + mean magnitude. That is enough to make the README credible.

---

# Phase 8 — Dashboard + Signal Strength + Signal Quality Panel (Sonnet)

**Model**: `/model sonnet`
**Budget**: $4–7

**Phase prompt (Message 3):**

```
We are on Phase 8: Dashboard.

Scope:
- Single src/main/resources/static/index.html
- Dark mode Tailwind via CDN, Chart.js via CDN, no build step, no framework
- Four sections:
  1. Header stats bar: markets tracked, alerts fired today, watched wallets,
     last poll time, ntfy topic copy button
  2. **SIGNAL QUALITY PANEL** (above the alert feed — this is the most
     important thing on the dashboard):
     - Small table fed by GET /api/signals/performance?horizon=t1h
     - Refreshed every 60 seconds
     - Columns: Detector, 1h Precision, 24h Precision, Count (7d)
     - Precision cells color-coded: >60% green, 50-60% yellow, <50% red
     - Count cells link to filtered alert feed
     - Also call /api/signals/performance?horizon=t24h for the 24h column
  3. Live alert feed (auto-refresh 10s), sorted by Signal Strength
  4. Watched markets grid with Chart.js mini charts
  5. Smart money tracker table
- Web Audio API soft ping on new critical alerts
- ntfy topic copy button in header
- All API endpoints from spec.md must be implemented in the corresponding
  @RestController classes (MarketController, WalletController,
  AlertController, StatsController) with Bean Validation and a
  GlobalExceptionHandler returning RFC 7807 problem+json

Signal Strength sort (v1 requirement):
Add a "Signal Strength" column to the alert feed. For each marketId with
any alert in the last 60 minutes, count how many DISTINCT detector types
fired (price_movement, statistical_anomaly, consensus, news_correlation).
Sort the alert feed descending by this count, tiebreak by most recent.
Markets with 3+ distinct detector types get a visual badge. Implement the
count in a new /api/alerts/by-signal-strength endpoint.

Hard rule: if at any point you feel the urge to add a bundler, React, or
npm, STOP and ask me. One HTML file. One.

Plan in 5 bullets, then "go".

Verification:
- Open http://localhost:8080 in browser
- Confirm all five sections render with real data
- Confirm the Signal Quality panel shows real precision numbers (not 0 or
  NaN — if it shows 0, that means Phase 7.5 didn't run long enough to
  produce outcomes yet; wait another hour and reload)
- Confirm the Signal Strength column in the alert feed shows counts
  correctly

Update PROGRESS.md. STOP. Do not start Phase 9.
```

**Watch for**: Framework scope creep. The "one HTML file" rule is the whole point — enforce it ruthlessly.

---

# Phase 9 — DLQs, Metrics, Resilience4j Polish (Sonnet)

**Model**: `/model sonnet`
**Budget**: $1–3

**Phase prompt (Message 3):**

```
We are on Phase 9: DLQs, Metrics, Resilience4j Polish.

Scope:
- Confirm every SQS queue has a DLQ wired with maxReceiveCount=5
- Implement every custom Micrometer metric from spec.md in CustomMetrics.java
  (both operational metrics and signal quality metrics)
- Audit every outbound HTTP call site and confirm it goes through Resilience4j

Prove each of these three things, do not just claim them:

1. DLQ proof: for each of the 3 DLQs, show the awslocal CLI command and its
   output proving the DLQ exists and is wired to its main queue as the
   redrive target.

2. Metrics proof: for each custom metric in spec.md, curl
   http://localhost:8080/actuator/prometheus and grep for the metric name.
   Show me the grep output for each. If any are missing, add them.
   Required metrics (from spec.md):
     polysign.markets.tracked
     polysign.prices.polled
     polysign.alerts.fired
     polysign.alerts.notified
     polysign.sqs.queue.depth
     polysign.sqs.dlq.depth
     polysign.http.outbound.latency
     polysign.wallet.trades.ingested
     polysign.signals.precision
     polysign.signals.magnitude.mean
     polysign.signals.sample.count
     polysign.outcomes.evaluated
     polysign.archive.snapshots.written

3. Resilience4j proof: show me a before/after for each outbound HTTP call
   site (MarketPoller, PricePoller, WalletPoller, RssPoller,
   NotificationConsumer, CLOB book call in the detectors) proving it's
   wrapped. Then: temporarily point one poller at http://localhost:9 (a
   dead port), run it, and show me the circuit breaker opening in the logs.
   Revert after.

Update PROGRESS.md with all three proofs. STOP. Do not start Phase 10.
```

---

# Phase 10 — Testcontainers Integration Test + CI (Sonnet)

**Model**: `/model sonnet`
**Budget**: $2–4
**This is the single most valuable test in the project. Do not cut corners.**

**Phase prompt (Message 3):**

```
We are on Phase 10: Testcontainers Integration Test + CI.

Scope:
- PolySignIntegrationTest.java using Testcontainers with
  localstack/localstack:3.8 (pinned, per CONVENTIONS.md)
- Test spins up LocalStack, runs BootstrapRunner to create all tables/
  queues, then does:
  1. Seeds fake price snapshots showing a 10% move with delta-p > 0.03
     (must pass Phase 4.5 filters)
  2. Runs PriceMovementDetector
  3. Asserts: exactly one row in alerts table with expected marketId
  4. Asserts: exactly one message in alerts-to-notify SQS queue
  5. Runs the detector a SECOND time on the same data
  6. Asserts: no new rows, no new messages (idempotency)
  7. Seeds a T+15min snapshot showing a confirmed move
  8. Runs AlertOutcomeEvaluator
  9. Asserts: exactly one row in alert_outcomes with horizon=t15m and
     wasCorrect=true (closes the signal quality loop in one test)

- .github/workflows/ci.yml: triggers on push and pull_request, Java 25
  Temurin, cache Maven, runs `mvn -B verify`

Mutation proof (MANDATORY): after the test passes, comment out the
alertService.create(...) call in PriceMovementDetector, rerun the test,
show me it FAILS red. Then revert and show me it passes green again. This
proves the test actually catches regressions.

Push to GitHub, confirm the CI badge goes green, add the badge URL to
PROGRESS.md and the top of README.md (if README exists yet; if not, save
the badge URL for Phase 11).

Update PROGRESS.md. STOP. Do not start Phase 11.
```

**Watch for**: The mutation proof is mandatory. If the test still passes with `alertService.create(...)` commented out, the test is broken and must be fixed before proceeding.

---

# Phase 11 — README + DESIGN.md (OPUS, max effort)

**Model**: `/model opus`
**Budget**: $5–12
**Do not start this phase with less than $13 remaining.**

**Phase prompt (Message 3):**

```
We are on Phase 11: README.md and DESIGN.md.

These are the documents an Amazon reviewer will read before looking at any
code. Quality matters more than speed.

IMPORTANT: The live AWS deployment happens in Phase 12, AFTER this phase.
So when writing the README, leave placeholders for the live demo URL and
the CloudWatch/cost screenshots. I will fill them in after Phase 12 and
then you will do a final consistency pass.

Scope:
- README.md per the "README Must Include" section of spec.md
- DESIGN.md per the "DESIGN.md Must Include" section of spec.md — all 13
  sections mandatory, 1,800-3,000 words
- deployment/aws-setup.md with copy-pasteable AWS CLI commands, sample
  task-definition.json, least-privilege IAM policy JSON (Phase 12 will
  create the actual numbered scripts; this phase creates the prose guide)

Rules for the writing itself:
- Do NOT summarize the spec. Write as if explaining decisions to a
  skeptical senior engineer who will ask "why not X?" for every choice.
- Every "why DynamoDB" / "why SQS" / "why not Kafka" section must
  acknowledge honest tradeoffs, not just benefits.
- The idempotency section in DESIGN.md MUST include the worked example
  from PROGRESS.md Phase 3: the composite-key bug (attribute_not_exists
  on alertId alone didn't dedupe because createdAt changed each cycle),
  the fix (deterministic bucketed createdAt), and the 3-run proof table
  with the specific alertId 845f165c...
- Include the signal-quality story from Phase 4.5: the 0.0045 → 0.0055
  tail-zone noise example, the delta-p floor fix, before/after alert
  counts. This demonstrates product judgment, not just engineering.
- The Signal Quality Methodology section (new v2) must explain:
    - What precision means and why it is not accuracy
    - The 0.5pp dead zone rationale
    - Horizon-based vs resolution-based evaluation
    - Known biases: survivor bias, lookback bias, clustering
    - Current measured numbers with sample sizes (pull from
      /api/signals/performance at write time)
- The scaling story must name specific bottlenecks in order (Polymarket
  rate limits first, then price_snapshots write throughput, then detector
  fan-out) and say what you would do about each.
- The "what I would do differently on AWS proper" section is the Amazon
  talking point — make it concrete (Lambda names, EventBridge rules,
  CloudWatch alarm thresholds tuned by signal-quality data, X-Ray
  sampling rate).
- In the signal-strategy section, explain the tier-1-to-tier-4 thinking:
  noise vs volatility vs information events vs edge. Explain why the four
  detectors together are more valuable than any one alone — signal
  overlap (Phase 8 Signal Strength sort) + measured precision (Phase 7.5
  signal quality) are the product.

Write README.md first, show it to me, wait for feedback.
Then DESIGN.md, show it to me, wait for feedback.
Then deployment/aws-setup.md.

After all three are done, do one final pass reading all three end-to-end
and fix any inconsistencies.

Update PROGRESS.md. Phase 11 is complete. The next (and final) phase is
Phase 12: Real AWS deployment.
```

**Watch for**: This is your interview artifact. If the README reads like "a description of what was built," push back — it should read like "a defense of the engineering decisions I made, by someone who knows what the alternatives were." The "what I'd do differently on AWS" section, the Phase 4.5 signal-quality story, and the Phase 7.5 measured-precision table are your three best differentiators. Do not let them be generic.

---

# Phase 12 — Real AWS Deployment (Sonnet) ⭐ NEW

**Model**: `/model sonnet`
**Budget**: $3–6 Claude Code + $15–30/month ongoing AWS
**Prerequisites**:
- AWS account with billing alarms set
- AWS CLI installed and configured with an Administrator profile
- Decided: ALB ($17/mo extra) or EC2 fallback ($11/mo, no ALB)

**Phase prompt (Message 3):**

```
We are on Phase 12: Real AWS Deployment. Phase 11 finalized README and
DESIGN. Now we put the project on the internet.

Read the "Deployment to Real AWS" section of spec.md in full. Confirm the
target topology and the cost ceiling ($30/month) back to me in one
sentence. Then wait for "go".

Prerequisite check (do this before proposing any code):
1. Confirm I have an AWS account with billing alerts enabled. Ask me for
   a screenshot or the alarm names if I am not sure.
2. Ask me which URL option I want:
     (a) Raw ALB DNS (free, ugly URL like
         polysign-alb-xxxx.us-east-1.elb.amazonaws.com)
     (b) Custom domain with ACM HTTPS (requires me to already own a domain)
     (c) EC2 t4g.small instead of Fargate+ALB (cheapest, $11/mo total,
         direct IP access)
3. Confirm I have the AWS CLI installed and configured on my laptop with
   a profile that has AdministratorAccess (for initial setup only — the
   running task will use a tight IAM role).
4. Estimate the monthly cost for my specific config (based on my URL
   choice in step 2) and warn me if it exceeds $30/month.

Wait for my answers to steps 1-4 before proceeding.

CHECKPOINT 1 — Infrastructure scripts

Produce these files in deployment/:

- 01-create-tables.sh — creates all 8 DynamoDB tables on-demand, with
  GSIs and TTLs matching the LocalStack BootstrapRunner exactly.
  Idempotent (checks if table exists before creating).
- 02-create-queues.sh — 3 main + 3 DLQ with redrive policies
  (maxReceiveCount=5). Idempotent.
- 03-create-bucket.sh — S3 bucket polysign-archives-${ACCOUNT_ID}-${REGION}
  with versioning enabled and a lifecycle rule (snapshots/ prefix → IA
  after 30 days, Glacier after 90 days). Idempotent.
- 04-create-ecr.sh — ECR repository for the polysign image. Idempotent.
- 05-create-iam.sh — task execution role (for ECR pull + CloudWatch
  Logs) + task role (least-privilege policy on the specific table/queue/
  bucket ARNs). Idempotent.
- 06-create-fargate.sh — ECS cluster, task definition, service. OR if I
  chose EC2 option, a simple user-data script for t4g.small that pulls
  the image from ECR and runs it. Idempotent.
- 07-create-alarms.sh — four CloudWatch alarms + SNS topic + email
  subscription for my address:
    1. DlqDepth_AlertsToNotify > 0 for 5 minutes
    2. TaskCpuUtilization > 80% for 10 minutes (or EC2 equivalent)
    3. DynamoDb_Alerts_ThrottledRequests > 0 for 1 minute
    4. NoAlertsFiredForOneHour composite alarm on
       polysign.alerts.fired == 0
  Idempotent.
- iam-policy.json — least privilege on the specific ARNs created above.
- task-definition.json — parameterized with ${ACCOUNT_ID}, ${REGION},
  ${IMAGE_TAG}.
- aws-setup.md (update from Phase 11) — step-by-step run order for the
  scripts with expected output for each.

All scripts must be idempotent (re-runnable without breaking) and print
clear ✓ success / ✗ failure messages. No Terraform, no CDK — just bash +
AWS CLI.

Also update:
- application-aws.yml — ensure it reads from real AWS (no
  endpointOverride), uses DefaultCredentialsProvider, enables
  micrometer-registry-cloudwatch2
- pom.xml — add micrometer-registry-cloudwatch2 dependency (scope should
  be unaffected by the local profile)
- Dockerfile — ensure it builds a slim production image

Show me all the scripts and the config changes. Do not run anything yet.
I will review and run them manually from my laptop. Stop here.

CHECKPOINT 2 — Deploy and chaos proof

After I confirm the infrastructure is up and the task is running:

1. Confirm the task is RUNNING and /actuator/health returns green through
   the public URL.
2. Run the three chaos experiments from spec.md §"Chaos proof" and
   capture the evidence files in deployment/chaos/:
     a. Polymarket circuit breaker opens
     b. DLQ alarm fires (temporarily point ntfy at a dead host)
     c. Idempotent restart (force-new-deployment mid-enqueue)
3. Take screenshots of all four CloudWatch alarms (even in OK state) and
   save to deployment/alarms-proof/.
4. Take a screenshot of the live dashboard with real data and save it.
5. Update the README with:
     - Live URL (above the fold)
     - Dashboard screenshot
     - Cost screenshot (AWS billing dashboard)
     - Alarms screenshots
     - A one-paragraph "lessons learned from the deploy" section

Commit everything. Update PROGRESS.md. Phase 12 is the true final phase.
The project is now interview-ready.

After all of this, run one last consistency pass on README + DESIGN.md +
aws-setup.md end-to-end and fix any stale references.
```

**Watch for**: ALB cost. If the $17/month ALB is too much and you haven't chosen the EC2 fallback yet, switch now. Also, make sure the billing alarm is ACTUALLY active before Claude Code runs any `aws` commands — it takes 24-48 hours to propagate.

---

# Ironclad rules for every session

1. **One phase per session.** Never mix phases.
2. **Always use `/Users/leonardholler/polysign` as the project path.**
3. **Standard kickoff first** (read the three files). Costs pennies, prevents drift.
4. **Read Claude Code's plan before saying "go".** Fixing prose is cheap, fixing code is not.
5. **Never stop mid-checkpoint.** Stop *between* phases if budget runs low.
6. **Commit check after every `/exit`.** Run `git log --oneline -10` from terminal — every phase should leave at least one commit.
7. **Opus only for Phase 5 and Phase 11.** Everything else Sonnet.
8. **If PROGRESS.md drifts from reality, fix it in a one-line edit before starting the next phase.**

---

# Budget math (v2)

| Phase | Model | Estimate |
|---|---|---|
| 5 — Stat Anomaly + orderbook | Opus | $5–13 |
| 6 — Wallet + Consensus | Sonnet | $2–5 |
| 7 — News + Correlation | Sonnet | $2–4 |
| **7.5 — Signal Quality (NEW)** | **Sonnet** | **$4–7** |
| 8 — Dashboard + Signal panels | Sonnet | $4–7 |
| 9 — DLQs + Metrics + R4j | Sonnet | $1–3 |
| 10 — Testcontainers + CI | Sonnet | $2–4 |
| 11 — README + DESIGN | Opus | $5–12 |
| **12 — Real AWS Deploy (NEW)** | **Sonnet** | **$3–6** |
| **Total remaining (Claude Code)** | | **$28–61** |

Plus AWS ongoing: $15–30 / month while the deployment is live.

You had ~$53 in Claude budget. Realistic top-up needed: **$15–25** for a comfortable buffer.

AWS costs are separate from your Claude budget — you confirmed you can handle those outside of it.
