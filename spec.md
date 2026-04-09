# PolySign — Build Spec (v2)

Build **PolySign**, a real-time event-processing and anomaly-detection system for Polymarket prediction markets. It combines price-movement detection, on-chain "smart money" wallet tracking, news correlation, and **measured signal quality (backtesting)**, and pushes alerts to my phone when high-signal events occur.

**This is a monitoring and alerting system. It does NOT place trades. It has zero write access to any wallet. All trading decisions are made manually by the user.**

The project is being built as a portfolio piece for an Amazon SDE application, so architectural clarity, AWS idiomatic usage, operational excellence (retries, DLQs, idempotency, metrics), **measured outcomes (signal precision)**, and production-quality Java matter as much as the features themselves.

**v2 changes from v1 (see section at the end for the full changelog):**
- New "Signal Quality & Backtesting" subsystem — every alert is scored against forward price movement and on market resolution, precision per detector is exposed at `/api/signals/performance` and on the dashboard.
- Real AWS deployment is mandatory, not optional. The project ships with a live URL, CloudWatch alarms, and documented chaos experiments.
- Orderbook depth is captured at alert time and fed into the backtest.

## Tech Stack (non-negotiable)

- **Java 25** (the current LTS as of September 2025)
- **Spring Boot 3.5.5 or newer in the 3.5.x line** (Spring Web, Spring Scheduling, Spring Validation, Spring Actuator). **Do NOT use Spring Boot 4.0 for this project** — as of early 2026 Resilience4j does not yet officially support Spring Framework 7 / Spring Boot 4. Spring Boot 3.5.5+ is officially Java 25 ready and keeps the full ecosystem compatible.
- **Maven** (single module; use the Spring Boot parent POM)
- **AWS SDK for Java v2** (`software.amazon.awssdk:dynamodb-enhanced`, `sqs`, `s3`, `cloudwatch`)
- **DynamoDB Enhanced Client** with annotated bean classes — not the low-level client
- **LocalStack** for local development, with a real AWS deployment for production. **CRITICAL VERSION PINNING**: pin the Docker image to `localstack/localstack:3.8` in both `docker-compose.yml` and in the Testcontainers `LocalStackContainer` constructor. Later LocalStack tags require an auth token.
- **Testcontainers** (`org.testcontainers:localstack`, `junit-jupiter`) for integration tests
- **JUnit 5 + Mockito + AssertJ** for unit tests
- **Micrometer + Prometheus registry** via Spring Actuator for local metrics
- **Micrometer CloudWatch2 registry** under the `aws` profile for production metrics
- **Rome** (`com.rometools:rome`) for RSS parsing
- **Jackson** (comes with Spring Boot) for JSON
- **Lombok** — latest stable release. Prefer Java records for immutable DTOs where possible.
- **SLF4J + Logback** with JSON-structured logging (`net.logstash.logback:logstash-logback-encoder`)
- **Resilience4j** for retries, circuit breakers, and rate limiting on outbound HTTP
- **ntfy.sh** for push notifications (no signup, free, HTTP POST to publish)
- **Vanilla HTML + JS + Chart.js + Tailwind CDN** for the frontend — no build step
- **Docker + Docker Compose** for local orchestration
- **GitHub Actions** for CI (build + test on push)
- **Bash + AWS CLI** for production deployment scripts (no Terraform, no CDK — scripts are the story)

Do NOT pull in Kotlin, Gradle, Quarkus, Micronaut, or any non-standard alternatives. Stick to the boring, Amazon-idiomatic Java stack.

## What It Does

1. Polls Polymarket for all active markets every 60 seconds, storing price snapshots in a rolling history.
2. Polls configurable RSS news feeds and matches articles to relevant markets by keyword.
3. Watches a configurable list of "smart money" wallet addresses and records their trades as they happen.
4. Runs four alert engines that feed into a unified alert stream:
   - **Price movement detector** — flags markets that move ≥X% in ≤Y minutes on above-threshold volume.
   - **Statistical anomaly detector** — rolling z-score per market over the last 60 minutes; flags moves exceeding 3σ.
   - **Wallet activity detector** — flags large trades by watched wallets; fires a stronger "consensus" alert when ≥3 wallets position the same direction on the same market within 30 minutes.
   - **News correlation detector** — flags when breaking news strongly matches a high-volume market.
5. Captures orderbook depth (`spreadBps`, `depthAtMid`) at every alert fire, so book quality can be attributed to signal quality downstream.
6. Alerts are delivered via ntfy.sh push AND shown in a dashboard.
7. Every alert is logged to DynamoDB with a 30-day TTL for analysis and threshold tuning.
8. **Continuously evaluates the signal quality of every alert.** Each alert is re-scored at fixed horizons (T+15min, T+1h, T+24h) and on market resolution. Per-detector precision / magnitude / count are exposed at `/api/signals/performance` and on the dashboard. This closes the feedback loop — PolySign can tell you whether its own signals are working.
9. Dashboard shows the signal quality panel, live alert feed, watched markets with price charts, and recent whale trades.

## Data Sources (all free, all public)

- **Polymarket Gamma API** for market metadata: `https://gamma-api.polymarket.com/markets?active=true&closed=false&limit=200`
- **Polymarket CLOB API** for prices and orderbook: `https://clob.polymarket.com/` (`/midpoint`, `/price`, `/book`). Verify exact endpoints with a live test at the start of Phase 2.
- **Polymarket Data API** for wallet activity: `https://data-api.polymarket.com/`. Verify `/trades?user=` and `/positions?user=` at the start of Phase 6. Fall back to Polygon RPC if needed.
- **Polygon RPC** (free public): `https://polygon-rpc.com`
- **RSS feeds**: Reuters, AP, BBC, ESPN, Reddit worldnews, Reddit politics — hardcode URLs in `application.yml`.
- **ntfy.sh**: `https://ntfy.sh/{topic}` — POST plain text. No auth. Use a hard-to-guess topic like `polysign-leonard-x7k2`.

## Seed Wallet Watchlist

Create `src/main/resources/watched_wallets.json` with 10 **placeholder** entries. Add a code comment and README section telling the user to replace them from `https://polymarket.com/leaderboard`. Format:

```json
[
  {"address": "0x0000000000000000000000000000000000000000", "alias": "PLACEHOLDER_01", "category": "politics", "notes": "Replace with a top politics trader from the leaderboard"}
]
```

The file is loaded at startup into the `watched_wallets` DynamoDB table if not already present (idempotent bootstrap).

## DynamoDB Schema

Use the DynamoDB Enhanced Client. Every table has a corresponding annotated bean class and a typed `DynamoDbTable<T>` wrapper bean defined in a `DynamoConfig` class.

**markets**
- PK: `marketId` (S)
- Attributes: `question`, `category`, `endDate`, `volume`, `volume24h`, `yesTokenId`, `outcomes`, `keywords`, `isWatched`, `updatedAt`
- GSI: `category-updatedAt-index` on (`category`, `updatedAt`)

**price_snapshots**
- PK: `marketId` (S)
- SK: `timestamp` (S, ISO-8601)
- Attributes: `yesPrice`, `noPrice`, `volume24h`, `midpoint`
- TTL attribute: `expiresAt` (epoch seconds, **30 days** out — extended from 7 days in v1 to allow 24-hour look-ahead backtesting from hot storage)
- Query pattern: "get all snapshots for market X in the last 60 minutes"

**articles**
- PK: `articleId` (S, SHA-256 of URL)
- Attributes: `title`, `url`, `source`, `publishedAt`, `summary`, `keywords`, `s3Key`

**market_news_matches**
- PK: `marketId`
- SK: `articleId`
- Attributes: `score`, `matchedKeywords`, `createdAt`

**watched_wallets**
- PK: `address` (S, lowercase)
- Attributes: `alias`, `category`, `notes`, `lastSyncedAt`

**wallet_trades**
- PK: `address`
- SK: `timestamp`
- Attributes: `marketId`, `marketQuestion`, `side`, `outcome`, `sizeUsdc`, `price`, `txHash`
- GSI: `marketId-timestamp-index` on (`marketId`, `timestamp`)

**alerts**
- PK: `alertId` (S, deterministic idempotency key)
- SK: `createdAt`
- Attributes: `type`, `severity`, `marketId`, `title`, `description`, `metadata` (includes `spreadBps`, `depthAtMid` when captured), `wasNotified`, `link`
- GSI: `marketId-createdAt-index`
- TTL attribute: `expiresAt` (30 days out)

**alert_outcomes** (new in v2)
- PK: `alertId` (S) — same ID as the row in `alerts`
- SK: `horizon` (S) — one of `t15m`, `t1h`, `t24h`, `resolution`
- Attributes: `type`, `marketId`, `firedAt`, `evaluatedAt`, `priceAtAlert`, `priceAtHorizon`, `directionPredicted`, `directionRealized`, `wasCorrect` (BOOL, nullable for flat), `magnitudePp`, `spreadBpsAtAlert` (nullable), `depthAtMidAtAlert` (nullable)
- GSI: `type-firedAt-index` on (`type`, `firedAt`) for per-detector aggregation
- TTL: none. Outcomes are cheap and the whole point is long-term measurement.

Include a `DESIGN.md` section explaining why DynamoDB over RDS: access patterns are all single-key or GSI lookups; TTL replaces cron cleanup; capacity scales per-table; the schema maps naturally to an event-sourced append log. Mention the tradeoffs honestly (no joins, eventual consistency on GSIs, item-size limits).

## SQS Queues (each with a dead-letter queue)

Every main queue has a corresponding DLQ with `maxReceiveCount = 5`. The app exposes DLQ depth as a Micrometer gauge.

- `news-to-process` → `news-to-process-dlq`
- `wallet-trades-to-process` → `wallet-trades-to-process-dlq`
- `alerts-to-notify` → `alerts-to-notify-dlq`

All producers set a `MessageDeduplicationId` for idempotency. Consumers are idempotent: processing the same message twice must never produce two alerts or two DB writes.

## S3 Buckets

- `polysign-archives` — raw article HTML at `articles/{yyyy}/{MM}/{dd}/{articleId}.html`, plus daily snapshot rollups at `snapshots/{yyyy}/{MM}/{dd}/{marketId}.jsonl.gz` for backtesting beyond the 30-day hot-storage TTL.

## Idempotency (critical — key Amazon talking point)

- **Alert IDs are deterministic**: `alertId = SHA-256(type + marketId + bucketedTimestamp + canonicalPayloadHash)` where `bucketedTimestamp` is rounded down to the dedupe window for that alert type.
- Writes to `alerts` use `PutItem` with `attribute_not_exists(alertId)`. `ConditionalCheckFailedException` is logged at DEBUG and swallowed.
- Writes to `alert_outcomes` use `attribute_not_exists(horizon)` on the composite key — running the evaluator twice on the same alert cannot produce two outcome rows at the same horizon.
- `price_snapshots` writes are skipped when the latest stored snapshot has the same price.
- Document the idempotency model in `DESIGN.md` with a worked example.

## Resilience (key talking point)

- All outbound HTTP calls (Polymarket Gamma, Polymarket CLOB, Polygon RPC, RSS, ntfy.sh) go through Resilience4j:
  - Retry: 3 attempts, exponential backoff, jitter.
  - Circuit breaker: opens at 50% failure over a 20-call sliding window, half-opens after 30s.
  - Rate limiter on CLOB (10 calls/sec).
- A failed poll for one market or one wallet must NEVER crash the scheduler. Catch at the per-item level, log with `correlationId`, increment `polysign.poll.failures`, continue.
- Graceful shutdown drains in-flight SQS messages before exiting.

## Metrics (expose at `/actuator/prometheus` locally and CloudWatch in production)

Custom Micrometer metrics:

**Operational:**
- `polysign.markets.tracked` (gauge)
- `polysign.prices.polled` (counter, tag: `status`)
- `polysign.alerts.fired` (counter, tags: `type`, `severity`)
- `polysign.alerts.notified` (counter, tag: `status`)
- `polysign.sqs.queue.depth` (gauge, tag: `queue`)
- `polysign.sqs.dlq.depth` (gauge, tag: `queue`)
- `polysign.http.outbound.latency` (timer, tag: `target`)
- `polysign.wallet.trades.ingested` (counter)

**Signal quality (v2):**
- `polysign.signals.precision` (gauge, tags: `type`, `horizon`)
- `polysign.signals.magnitude.mean` (gauge, tags: `type`, `horizon`)
- `polysign.signals.sample.count` (gauge, tags: `type`, `horizon`)
- `polysign.outcomes.evaluated` (counter, tags: `type`, `horizon`)
- `polysign.archive.snapshots.written` (counter)

## Alert Engines

### 1. Price Movement Detector (threshold-based)
Scheduled every 60 seconds.
- For each active market, query last 60 minutes of snapshots.
- Fire alert if price moved ≥ 8% in ≤ 15 minutes AND 24h volume ≥ $50,000.
- Minimum absolute delta: `|toPrice - fromPrice| >= 0.03`.
- Extreme-zone filter: skip if both prices < 0.05 or both > 0.95.
- Higher severity if `isWatched = true`.
- Dedupe window: 30 minutes, bypassed if move exceeds 2× threshold.
- **Orderbook capture**: at alert fire, call `clob.polymarket.com/book?token_id=...` once; compute `spreadBps` and `depthAtMid`; attach to alert metadata. If the CLOB call fails (500ms budget), fire the alert with null book fields.

### 2. Statistical Anomaly Detector (z-score)
Scheduled every 60 seconds, runs after the threshold detector.
- For each active market with ≥ 20 snapshots in the last 60 minutes, compute rolling mean and stddev of 1-minute returns.
- If the most recent return's z-score exceeds 3.0 AND 24h volume ≥ $50,000, fire a `warning` alert of type `statistical_anomaly`.
- Apply the same `min-delta-p` (0.03) and extreme-zone (0.05/0.95) filters as the price detector.
- Include z-score, window size, mean, stddev in alert metadata.
- **Orderbook capture**: same as Price Movement Detector — attach `spreadBps` and `depthAtMid` on fire.
- Unit test heavily with synthetic series: flat, trending, noisy, spiking, gradual acceleration, insufficient history, high-vol market, low-volume market.

### 3. Wallet Activity Detector
Scheduled every 60 seconds.
- For each watched wallet, fetch new trades since `lastSyncedAt`.
- Write to `wallet_trades` (idempotent on `txHash`).
- Fire `info` alert on individual trades > $5,000.
- **Consensus signal**: query `marketId-timestamp-index` GSI — if ≥ 3 distinct watched wallets traded the same market same direction within 30 minutes, fire `critical` alert of type `consensus`.

### 4. News Correlation Detector
SQS consumer on `news-to-process`.
- Extract keywords, score against active markets.
- Fire `warning` alert if score ≥ 0.5 AND market volume ≥ $100,000.
- Include article title, source, link, matched keywords in metadata.

## Signal Quality & Backtesting (v2)

This subsystem is the single most important addition in v2. A monitoring system that cannot tell you whether its signals work is indistinguishable from a random number generator with nice UI. PolySign closes the loop by scoring every fired alert against forward price movement and final market resolution.

### `SnapshotArchiver`

- `@Scheduled` daily at 04:00 UTC
- Reads the last 24h of `price_snapshots` per tracked market
- Writes one gzipped JSON-Lines file per market to `s3://polysign-archives/snapshots/{yyyy}/{MM}/{dd}/{marketId}.jsonl.gz`
- Idempotent on S3 key
- Emits `polysign.archive.snapshots.written` counter

### `AlertOutcomeEvaluator`

- `@Scheduled` every 5 minutes
- Finds alerts fired between 15 minutes ago and 25 hours ago whose next-due horizon row does not yet exist in `alert_outcomes`.
- For each alert, for each due horizon (`t15m`, `t1h`, `t24h`):
  - Fetch the snapshot closest to `firedAt + horizon` from `price_snapshots` (fall back to S3 cold storage if older than 30 days)
  - Compute `magnitudePp`, `directionRealized`, `wasCorrect`
  - Write the row with `attribute_not_exists(horizon)` for idempotency
- **0.5pp dead zone**: if `|magnitudePp| < 0.005`, `directionRealized = "flat"` and `wasCorrect = null` (excluded from precision denominator). This prevents rewarding random noise in flat markets.
- Emits `polysign.outcomes.evaluated` counter tagged by `type` and `horizon`.

### `ResolutionSweeper`

- `@Scheduled` every 6 hours
- Finds markets with `closed = true`
- For each such market, finds every alert ever fired on it
- Computes the `resolution` outcome row using the final outcome price (0 or 1) as `priceAtHorizon`
- Idempotent via the same composite-key conditional write

### `SignalPerformanceService` + `SignalPerformanceController`

- `GET /api/signals/performance?type=&horizon=&since=`
- Query params: `type` optional filter, `horizon` one of `t15m|t1h|t24h|resolution` (default `t1h`), `since` ISO-8601 (default 7 days ago)
- Scans `alert_outcomes` via the `type-firedAt-index` GSI
- Returns per-detector aggregates: `count`, `precision`, `avgMagnitudePp`, `medianMagnitudePp`, `meanAbsMagnitudePp`
- Response shape:

```json
{
  "horizon": "t1h",
  "since": "2026-04-01T00:00:00Z",
  "detectors": [
    {
      "type": "price_movement",
      "count": 412,
      "precision": 0.58,
      "avgMagnitudePp": 0.021,
      "medianMagnitudePp": 0.014,
      "meanAbsMagnitudePp": 0.033
    }
  ]
}
```

**Precision definition**: fraction of alerts whose realized direction at the horizon matches the predicted direction, excluding the dead-zone flat cases from the denominator. Document this in DESIGN.md.

### Known biases to acknowledge in DESIGN.md

- **Survivor bias** in the resolution sweep (closed markets are a non-random subset of all markets).
- **Lookback bias** at horizon boundaries if snapshot granularity is coarser than 1 minute.
- **Clustering** of correlated alerts (one big news event produces many correlated alerts, inflating the apparent sample size).

## ntfy.sh Notification Worker

Spring `@Component` that long-polls `alerts-to-notify` and POSTs to ntfy.sh.

- Severity → ntfy priority: `info=default`, `warning=high`, `critical=max`
- Headers: `Title`, `Priority`, `Tags`, `Click` (polymarket deep link)
- On success, flip `wasNotified = true` and delete the SQS message.
- On failure, leave the message for SQS to redeliver; after 5 attempts it goes to the DLQ.

## API Endpoints (Spring Web `@RestController`)

- `GET /` → `index.html`
- `GET /api/markets?category=&watchedOnly=&limit=`
- `GET /api/markets/{marketId}`
- `GET /api/markets/{marketId}/price-history?windowMinutes=60`
- `GET /api/markets/{marketId}/news`
- `GET /api/markets/{marketId}/whale-trades`
- `POST /api/markets/{marketId}/watch`
- `GET /api/wallets`
- `GET /api/wallets/{address}/trades?limit=50`
- `POST /api/wallets` / `DELETE /api/wallets/{address}`
- `GET /api/alerts?limit=100&severity=&type=&since=`
- `GET /api/alerts/by-signal-strength` — alerts sorted by count of distinct detector types firing on the same market in the last 60 minutes
- `POST /api/alerts/{alertId}/mark-reviewed`
- `GET /api/signals/performance?type=&horizon=&since=` (v2)
- `GET /api/stats`
- `GET /actuator/health`
- `GET /actuator/prometheus`

Use Bean Validation on request bodies. `@ControllerAdvice` global exception handler returns RFC 7807 `application/problem+json`.

## Dashboard UI

Single `index.html`. Dark mode Tailwind via CDN. Chart.js via CDN. No build step.

### Header
Stats bar: markets tracked, alerts fired today, watched wallets, last poll time. ntfy topic with copy button.

### Signal Quality Panel (v2, above the alert feed)
Small table fed by `/api/signals/performance?horizon=t1h` refreshed every 60s:

```
Detector              | 1h Precision | 24h Precision | Count (7d)
price_movement        |     58%      |     53%       |    412
statistical_anomaly   |     63%      |     59%       |    178
consensus             |     71%      |     66%       |     23
news_correlation      |     49%      |     47%       |     61
```

Precision cells color-coded: >60% green, 50–60% yellow, <50% red. Count cells link to the filtered alert feed. **This panel is above the fold — it is the most important thing on the dashboard.**

### Live Alert Feed
Auto-refresh every 10s. Color-coded by severity. Sorted by **Signal Strength** (count of distinct detector types firing on the same market in the last 60 minutes), tiebreak by most recent. A market with 3+ distinct detector types firing gets a badge. Soft ping via Web Audio API on new critical alerts.

### Watched Markets Grid
Cards per watched market: question, category, prices, 1-hour Chart.js chart, 24h volume, whale trade count, news count.

### Smart Money Tracker
Table of watched wallets: alias, truncated address, category, last trade time, total trades, recent direction.

## Project Structure

```
polysign/
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── .github/workflows/ci.yml
├── deployment/                         # v2 — real AWS deployment
│   ├── aws-setup.md
│   ├── 01-create-tables.sh
│   ├── 02-create-queues.sh
│   ├── 03-create-bucket.sh
│   ├── 04-create-ecr.sh
│   ├── 05-create-iam.sh
│   ├── 06-create-fargate.sh
│   ├── 07-create-alarms.sh
│   ├── iam-policy.json
│   ├── task-definition.json
│   ├── alarms-proof/                   # screenshots of all 4 alarms
│   └── chaos/                          # evidence from 3 chaos experiments
├── src/
│   ├── main/java/com/polysign/
│   │   ├── PolySignApplication.java
│   │   ├── config/                     # Aws, Dynamo, Http, Scheduling, BootstrapRunner
│   │   ├── common/                     # CorrelationId, AppClock, Result<T>
│   │   ├── model/                      # Market, PriceSnapshot, Article, ..., Alert, AlertOutcome
│   │   ├── poller/                     # Market, Price, Wallet, Rss pollers
│   │   ├── detector/                   # PriceMovement, StatisticalAnomaly, WalletActivity, Consensus, NewsCorrelation
│   │   ├── alert/                      # AlertService, AlertIdFactory
│   │   ├── backtest/                   # v2 — SnapshotArchiver, AlertOutcomeEvaluator, ResolutionSweeper, SignalPerformanceService
│   │   ├── notification/               # NotificationConsumer
│   │   ├── processing/                 # KeywordExtractor, NewsMatcher, NewsConsumer
│   │   ├── api/                        # Controllers + GlobalExceptionHandler
│   │   └── metrics/                    # CustomMetrics
│   └── main/resources/
│       ├── application.yml, application-local.yml, application-aws.yml
│       ├── logback-spring.xml
│       ├── watched_wallets.json
│       └── static/index.html
├── src/test/java/com/polysign/
│   ├── detector/, alert/, processing/, backtest/    # unit tests
│   └── integration/PolySignIntegrationTest.java     # Testcontainers
├── DESIGN.md
└── README.md
```

## Docker Compose

Services: `localstack`, `polysign`. Single JAR runs everything in one process for local dev. Boundaries are enforced in code, not containers.

## Deployment to Real AWS (v2 — mandatory)

The project ships deployed on real AWS, with a URL a reviewer can click. A portfolio artifact a recruiter can visit is worth more than any README paragraph.

### Target topology

- **Compute**: ECS Fargate, 1 task, 0.25 vCPU / 0.5 GB RAM, single container running the Spring Boot JAR. Monolithic design maps cleanly to a single-task service.
- **Public URL**: by default, use the raw ALB DNS (`polysign-alb-xxxx.us-east-1.elb.amazonaws.com`). No domain purchase required. If a domain is available, use ACM for HTTPS; otherwise run HTTP-only (no auth on the app means HTTPS adds no real security).
  - **Cost fallback**: if the ALB's $17/mo is unacceptable, run on a single EC2 t4g.small ($11/mo) with the task exposing port 8080 directly. Document the tradeoff in the README.
- **State**: real DynamoDB (on-demand, 8 tables), real SQS (3 main + 3 DLQ), real S3 (`polysign-archives-${ACCOUNT_ID}-${REGION}`). Same bean code as LocalStack, no endpoint override under the `aws` profile.
- **Logs**: CloudWatch Logs via `awslogs` driver. One log group, 30-day retention. Existing JSON logback config is CloudWatch-compatible; Logs Insights queries on `correlationId` work out of the box.
- **Metrics**: CloudWatch EMF via `micrometer-registry-cloudwatch2` under the `aws` profile.
- **Secrets**: `NTFY_TOPIC` in Secrets Manager, injected into the task.
- **Cost ceiling**: < $30/month. See the cost table in the README.

### CloudWatch alarms (mandatory)

1. `DlqDepth_AlertsToNotify > 0 for 5 minutes` → SNS → email
2. `TaskCpuUtilization > 80% for 10 minutes` → SNS → email
3. `DynamoDb_Alerts_ThrottledRequests > 0 for 1 minute` → SNS → email
4. `NoAlertsFiredForOneHour` composite alarm → SNS → email (catches silent failures)

Screenshots of all 4 alarms (even in OK state) go in `deployment/alarms-proof/`.

### Chaos proof (mandatory)

Run these three experiments against the live deployment and capture evidence in `deployment/chaos/`:

1. **Polymarket circuit breaker opens** — block egress to `*.polymarket.com` via SG rule, watch the breaker state transitions in CloudWatch Logs, revert. Save log lines to `01-polymarket-breaker.log`.
2. **DLQ alarm fires** — point ntfy base URL at a dead host, let 5 alerts fail through to the DLQ, confirm the email arrives. Save email screenshot + DLQ depth graph to `02-dlq-alarm.png`.
3. **Idempotent restart** — fire an alert, `aws ecs update-service --force-new-deployment` mid-enqueue, confirm no duplicate alert or notification. Save before/after counts + log lines to `03-idempotent-restart.log`.

Each experiment maps to an Amazon Leadership Principle: Operational Excellence, Bias for Action, Ownership.

### `deployment/` scripts

All scripts in bash + AWS CLI (no Terraform, no CDK). Each script is idempotent (re-runnable). Scripts are numbered in run order: 01-create-tables, 02-create-queues, 03-create-bucket, 04-create-ecr, 05-create-iam, 06-create-fargate, 07-create-alarms.

### README additions

The README must include, above the fold:
- Live demo URL (or screenshot if the deployment has been spun down for cost)
- CloudWatch dashboard screenshot showing markets tracked, alerts fired today, DLQ depth
- Signal quality panel screenshot with real numbers
- Actual monthly AWS cost with billing dashboard screenshot
- A one-paragraph "lessons learned from the deploy" section

## CI (GitHub Actions)

`.github/workflows/ci.yml`:
- Triggers on push and pull_request
- Java 25 Temurin, cache Maven, `mvn -B verify`
- Testcontainers tests run via default Docker on `ubuntu-latest`

Green CI badge at the top of the README.

## Testing Requirements

- **Unit tests** for every detector. Statistical detector: synthetic series (flat, linear, noisy, spike, gradual drift, insufficient history, high-vol market, low-volume market).
- **Unit tests** for `AlertIdFactory` (determinism, no collisions, bucket internals).
- **Unit tests** for `SnapshotArchiver`: empty day, multi-market day, S3 key format assertion.
- **Unit tests** for `AlertOutcomeEvaluator`: correct direction, wrong direction, dead-zone flat (excluded from precision), missing snapshot (graceful skip), idempotent re-run.
- **Unit tests** for `ResolutionSweeper`: up-alert on a YES-resolved market (correct), down-alert on the same market (incorrect).
- **Unit tests** for `KeywordExtractor`, `NewsMatcher`, `ConsensusDetector`.
- **Integration test** using Testcontainers LocalStack: spin up LocalStack, create tables/queues, insert fake price snapshots with a 10% move, run PriceMovementDetector, assert:
  1. Exactly one row in `alerts`
  2. Exactly one message in `alerts-to-notify`
  3. Re-running the detector produces no new rows (idempotency)
  4. After seeding a T+15min snapshot, running `AlertOutcomeEvaluator` produces one row in `alert_outcomes` with `wasCorrect = true` (closes the loop in one test)
- **Mutation proof**: comment out `alertService.create(...)` in the detector, re-run the integration test, show it fails red. Revert. Show green. Proves the test actually catches regressions.
- Target ≥ 70% line coverage on `detector/`, `alert/`, `backtest/`, `processing/`.

## README Must Include

- One-paragraph pitch framed as "a real-time event-processing and anomaly-detection system with measured signal quality".
- "Monitoring only, not a trading bot" disclaimer in the first 10 lines.
- CI status badge.
- **Live demo URL** (or screenshot if spun down).
- **Signal quality panel screenshot** with real numbers from the live deployment.
- Architecture ASCII diagram: pollers → DynamoDB → detectors → SQS → consumers → (DynamoDB + ntfy.sh), with the backtest loop (snapshots + alerts → evaluator → outcomes → performance API).
- Feature list.
- AWS services table: every table/queue/bucket with a one-line justification. Include "why DynamoDB over RDS" and "why SQS over in-process queues" in one-sentence form.
- Setup: `docker-compose up --build`, open `http://localhost:8080`.
- ntfy.sh phone setup.
- Configuring watched wallets and tuning thresholds.
- **"Running on Real AWS"** section pointing to `deployment/aws-setup.md`, with actual monthly cost.
- **CloudWatch alarms screenshots** (4 panels).
- Limitations: monitoring only; keyword matching misses semantic matches; stat detector needs more history on low-volume markets; consensus needs per-category tuning; signal quality is measured over a small sample on new deployments.
- Future work: embedding-based news matching, Kalshi integration, orderbook depth time-series, rewriting the detector pipeline as AWS Lambda + EventBridge.

## DESIGN.md Must Include

1. **Problem framing** — one paragraph.
2. **Data model** — per-table access patterns, key choices, GSI justifications.
3. **Write path** — trace a price poll from HTTP response → PricePoller → dedupe → DynamoDB → detector → alert → SQS → ntfy, one paragraph per hop.
4. **Idempotency** — deterministic alert ID scheme with a worked example (the Phase 3 composite-key bug, the fix, the 3-run proof table with a specific `alertId`).
5. **Signal quality story** — the Phase 4.5 tail-zone noise example (0.0045 → 0.0055), the delta-p floor fix, before/after alert counts. Demonstrates product judgment, not just engineering.
6. **Failure modes** — Polymarket down, ntfy down, DynamoDB throttles, SQS redelivery, worker crash mid-consume, DLQ behavior. For each: what the system does, what the user sees, how to recover.
7. **Why DynamoDB over RDS** — access patterns, TTL, scaling, tradeoffs (no joins, GSI eventual consistency, item size).
8. **Why SQS over in-process queues** — crash isolation, retry/DLQ, queue depth metrics.
9. **Why not Kafka** — operational overhead, SQS is simpler at this scale.
10. **Scaling story** — 200 → 20,000 markets and 10 → 10,000 wallets. Bottlenecks in order: Polymarket rate limits, then price_snapshots write throughput, then detector fan-out. What to do about each.
11. **What I would do differently on AWS proper** — Lambda + EventBridge for detectors, X-Ray tracing, Secrets Manager, CloudWatch alarm thresholds tuned by signal-quality data.
12. **Signal Quality Methodology** (v2) — what precision means here, why it's not accuracy, the 0.5pp dead zone rationale, horizon-based vs resolution-based evaluation, known biases (survivor, lookback, clustering), current measured numbers with sample sizes.
13. **Operational Runbook** (v2) — for each of the 3 chaos experiments, a paragraph describing what was done, what the system did, and which alarm fired. Cross-reference to `deployment/chaos/`.

Aim for 1,800–3,000 words. This is the document you will reread before your Amazon loop.

## Critical Implementation Notes

- **Verify Polymarket endpoints with live test requests at Phases 2 and 6.** Do not trust URLs blindly.
- **No trading code, no signing libraries, no wallet write access.**
- **Every outbound HTTP call through Resilience4j.** No naked WebClient calls.
- **Structured JSON logging only.** Every log line has a `correlationId` flowing poll → detector → alert → notification.
- **.gitignore** excludes `.env`, `target/`, `.idea/`, `*.iml`, `localstack_data/`, deployment secrets.
- **Commit in clean reviewable chunks per phase.** The git log is a portfolio artifact.

## Non-Goals (explicit)

- No trading, ever.
- No embedding-based news matching in v2. Keyword matching is v2's news layer; the statistical anomaly detector and the **backtest-measured signal quality scoring** are the "smart" layers for v2. Embeddings are v3.
- No user accounts, no auth on the API.
- No frontend framework. One HTML file.

## v2 Changelog (from v1)

- **Added Signal Quality & Backtesting subsystem.** New table `alert_outcomes`, new components `SnapshotArchiver`, `AlertOutcomeEvaluator`, `ResolutionSweeper`, `SignalPerformanceService`. New endpoint `/api/signals/performance`. New dashboard panel. New phase: 7.5.
- **Real AWS deployment is now mandatory**, not optional. New phase: 12. New `deployment/` scripts, alarms, chaos proofs.
- **Extended `price_snapshots` TTL** from 7 days to 30 days to support 24h look-ahead backtesting from hot storage.
- **Added orderbook depth capture** at alert fire: `spreadBps` and `depthAtMid` attached to alert metadata. Attributed by the backtest.
- **Added `alert_outcomes` table** with GSI `type-firedAt-index`.
- **Added signal quality metrics** to the Micrometer set.
- **Added `CustomMetrics` + CloudWatch EMF registry** under the `aws` profile.
- **Updated DESIGN.md requirements** with Signal Quality Methodology and Operational Runbook sections.

Build incrementally. Verify each phase before moving on. Do not skip the Testcontainers integration test in Phase 10 or the chaos proofs in Phase 12.
