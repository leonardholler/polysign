# PolySign — Build Spec

Build **PolySign**, a real-time event-processing and anomaly-detection system for Polymarket prediction markets. It combines price-movement detection, on-chain "smart money" wallet tracking, and news correlation, and pushes alerts to my phone when high-signal events occur.

**This is a monitoring and alerting system. It does NOT place trades. It has zero write access to any wallet. All trading decisions are made manually by the user.**

The project is being built as a portfolio piece for an Amazon SDE application, so architectural clarity, AWS idiomatic usage, operational excellence (retries, DLQs, idempotency, metrics), and production-quality Java matter as much as the features themselves.

## Tech Stack (non-negotiable)

- **Java 25** (the current LTS as of September 2025)
- **Spring Boot 3.5.5 or newer in the 3.5.x line** (Spring Web, Spring Scheduling, Spring Validation, Spring Actuator). **Do NOT use Spring Boot 4.0 for this project** — Spring Boot 4 is available but as of early 2026 Resilience4j does not yet officially support Spring Framework 7 / Spring Boot 4 (tracking issue: resilience4j/resilience4j#2351). Spring Boot 3.5.5+ is officially Java 25 ready (confirmed in spring-projects/spring-boot#47245) and keeps the full ecosystem compatible. This is the pragmatic sweet spot.
- **Maven** (single module; use the Spring Boot parent POM)
- **AWS SDK for Java v2** (`software.amazon.awssdk:dynamodb-enhanced`, `sqs`, `s3`)
- **DynamoDB Enhanced Client** with annotated bean classes — not the low-level client
- **LocalStack** for local development, with a clean path to real AWS (see Deployment section). **CRITICAL VERSION PINNING**: as of LocalStack 2026.03.0 (released March 23, 2026), `localstack/localstack:latest` requires a `LOCALSTACK_AUTH_TOKEN` environment variable and will fail to start without one. To avoid forcing the user to sign up for a LocalStack account for a portfolio project, **pin the Docker image to `localstack/localstack:3.8` in both `docker-compose.yml` and in the Testcontainers `LocalStackContainer` constructor**. This is the last pre-consolidation tag that runs without authentication. Document this clearly in the README under "Why is the LocalStack version pinned?".
- **Testcontainers** (`org.testcontainers:localstack`, `junit-jupiter`) for integration tests
- **JUnit 5 + Mockito + AssertJ** for unit tests
- **Micrometer + Prometheus registry** via Spring Actuator for metrics
- **Rome** (`com.rometools:rome`) for RSS parsing
- **Jackson** (comes with Spring Boot) for JSON
- **Lombok** — latest stable release only (Lombok 1.18.42 has known compilation bugs with `@Value` and `@Builder` on JDK 25; grab a newer version from Maven Central at build time). **Prefer Java records** for immutable DTOs, response bodies, and value objects wherever possible — records are more idiomatic in modern Java and avoid Lombok JDK-25 edge cases entirely. Use Lombok only for mutable entity classes where `@Getter`/`@Setter`/`@Slf4j` actually earn their keep.
- **SLF4J + Logback** with JSON-structured logging (`net.logstash.logback:logstash-logback-encoder`)
- **Resilience4j** for retries, circuit breakers, and rate limiting on outbound HTTP
- **ntfy.sh** for push notifications (no signup, free, HTTP POST to publish)
- **Vanilla HTML + JS + Chart.js + Tailwind CDN** for the frontend — no build step, served as a Spring static resource
- **Docker + Docker Compose** to orchestrate LocalStack + the app
- **GitHub Actions** for CI (build + test on push)

Do NOT pull in Kotlin, Gradle, Quarkus, Micronaut, or any non-standard alternatives. Stick to the boring, Amazon-idiomatic Java stack.

## What It Does

1. Polls Polymarket for all active markets every 60 seconds, storing price snapshots in a rolling history.
2. Polls configurable RSS news feeds and matches articles to relevant markets by keyword.
3. Watches a configurable list of "smart money" wallet addresses (known profitable Polymarket traders) and records their trades as they happen on-chain.
4. Runs four alert engines that all feed into a unified alert stream:
   - **Price movement detector** — threshold-based: flags markets that move ≥X% in ≤Y minutes on above-threshold volume.
   - **Statistical anomaly detector** — computes a rolling z-score per market over the last 60 minutes of price snapshots and flags markets whose latest move exceeds 3σ. This is the "ML-flavored" detector and is intentionally kept simple and explainable.
   - **Wallet activity detector** — flags trades by watched wallets; fires a much stronger "consensus" alert when ≥3 watched wallets position the same direction on the same market within a 30-minute window.
   - **News correlation detector** — flags when breaking news strongly matches an active high-volume market.
5. Alerts are delivered via ntfy.sh push notifications AND displayed in a dashboard.
6. Every alert is logged to DynamoDB with a 30-day TTL for later analysis and threshold tuning.
7. Dashboard shows the live alert feed, watched markets with price charts, and recent whale trades.

## Data Sources (all free, all public)

- **Polymarket Gamma API** for market metadata: `https://gamma-api.polymarket.com/markets?active=true&closed=false&limit=200`
- **Polymarket CLOB API** for prices/orderbook: `https://clob.polymarket.com/` — verify exact endpoints (`/book`, `/price`, `/midpoint`, `/trades`) with a live test request at the start of phase 2. Do not blindly trust endpoint names.
- **Polymarket Data API** for wallet activity: `https://data-api.polymarket.com/` — verify `/trades?user={address}` and `/positions?user={address}` at the start of phase 5. Fall back to Polygon RPC if needed.
- **Polygon RPC** (free public): `https://polygon-rpc.com`
- **RSS feeds**: Reuters, AP, BBC, ESPN, Reddit worldnews, Reddit politics — hardcode URLs in `application.yml`.
- **ntfy.sh**: `https://ntfy.sh/{topic}` — POST plain text body. No auth. Use a random hard-to-guess topic name like `polysign-leonard-x7k2`.

## Seed Wallet Watchlist

Create `src/main/resources/watched_wallets.json` with 10 **placeholder** entries. Do not invent real addresses. Add a code comment and a README section telling the user to replace them by browsing `https://polymarket.com/leaderboard`. Format:

```json
[
  {"address": "0x0000000000000000000000000000000000000000", "alias": "PLACEHOLDER_01", "category": "politics", "notes": "Replace with a top politics trader from the leaderboard"}
]
```

The file is loaded at startup into the `watched_wallets` DynamoDB table if not already present (idempotent bootstrap).

## DynamoDB Schema

Use the DynamoDB Enhanced Client. Every table should have a corresponding annotated bean class and a typed `DynamoDbTable<T>` wrapper bean defined in a `DynamoConfig` class.

**markets**
- PK: `marketId` (S)
- Attributes: `question`, `category`, `endDate`, `volume`, `outcomes` (list), `keywords` (set), `isWatched` (bool), `updatedAt`
- GSI: `category-updatedAt-index` on (`category`, `updatedAt`)

**price_snapshots**
- PK: `marketId` (S)
- SK: `timestamp` (S, ISO-8601, lexicographically sortable)
- Attributes: `yesPrice`, `noPrice`, `volume24h`, `midpoint`
- TTL attribute: `expiresAt` (epoch seconds, 7 days out)
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
- PK: `alertId` (S, deterministic idempotency key — see Idempotency section)
- SK: `createdAt`
- Attributes: `type`, `severity`, `marketId`, `title`, `description`, `metadata` (map), `wasNotified` (bool), `link`
- GSI: `marketId-createdAt-index`
- TTL attribute: `expiresAt` (30 days out)

Include a `DESIGN.md` section explaining why DynamoDB over RDS: access patterns are all single-key or GSI lookups; TTL replaces cron cleanup; capacity can scale per-table; the schema maps naturally to an event-sourced append log for price snapshots and trades. Mention the tradeoffs honestly (no joins, eventual consistency on GSIs, item-size limits).

## SQS Queues (each with a dead-letter queue)

Every main queue has a corresponding DLQ with `maxReceiveCount = 5`. The app exposes DLQ depth as a Micrometer gauge.

- `news-to-process` → `news-to-process-dlq`
- `wallet-trades-to-process` → `wallet-trades-to-process-dlq`
- `alerts-to-notify` → `alerts-to-notify-dlq`

All producers set a `MessageDeduplicationId` (or content-based hash in attributes) for idempotency even though these are standard queues. Consumers are written to be idempotent: processing the same message twice must never produce two alerts or two DB writes.

## S3 Buckets

- `polysign-archives` — raw article HTML for later reanalysis. Key format: `articles/{yyyy}/{MM}/{dd}/{articleId}.html`.

## Idempotency (critical — this is a key Amazon talking point)

- **Alert IDs are deterministic**, not random UUIDs. `alertId = SHA-256(type + marketId + bucketedTimestamp + canonicalPayloadHash)` where `bucketedTimestamp` is rounded down to the dedupe window for that alert type. This guarantees that re-running a detector on the same window cannot produce duplicate alerts, even across worker restarts or queue redeliveries.
- Writes to the `alerts` table use `PutItem` with a `attribute_not_exists(alertId)` condition. A `ConditionalCheckFailedException` is logged at DEBUG and swallowed — it means the alert was already emitted.
- `price_snapshots` writes are skipped when the latest stored snapshot has the same price (dedupe on no-change).
- Document the idempotency model in `DESIGN.md` with a short worked example.

## Resilience (also a key talking point)

- All outbound HTTP calls (Polymarket, Polygon RPC, RSS, ntfy.sh) go through Resilience4j with:
  - Retry: 3 attempts, exponential backoff, jitter.
  - Circuit breaker: opens after 50% failure rate over a 20-call sliding window, half-opens after 30s.
  - Rate limiter where appropriate (Polymarket calls capped at a sane per-second rate).
- A failed poll for one market or one wallet must NEVER crash the scheduler. Catch at the per-item level, log structured error with correlation ID, increment a `polysign.poll.failures` counter, continue.
- Graceful shutdown: the Spring context's shutdown hook drains in-flight SQS messages before exiting.

## Metrics (expose at `/actuator/prometheus`)

Custom Micrometer metrics to define explicitly:

- `polysign.markets.tracked` (gauge)
- `polysign.prices.polled` (counter, tag: `status=success|failure`)
- `polysign.alerts.fired` (counter, tags: `type`, `severity`)
- `polysign.alerts.notified` (counter, tag: `status`)
- `polysign.sqs.queue.depth` (gauge, tag: `queue`)
- `polysign.sqs.dlq.depth` (gauge, tag: `queue`)
- `polysign.http.outbound.latency` (timer, tag: `target`)
- `polysign.wallet.trades.ingested` (counter)

The README points a reader at `/actuator/prometheus` and `/actuator/health` and explains how these would wire into CloudWatch in a real deployment.

## Alert Engines

### 1. Price Movement Detector (threshold-based)
Scheduled every 60 seconds after price polling.
- For each active market, query last 60 minutes of snapshots.
- Fire alert if price moved ≥ 8% in ≤ 15 minutes AND 24h volume ≥ $50,000.
- Higher severity if `isWatched = true`.
- Configurable in `application.yml`: `polysign.detectors.price.thresholdPct`, `windowMinutes`, `minVolumeUsdc`.
- Dedupe window: 30 minutes, bypassed if move exceeds 2× threshold.

### 2. Statistical Anomaly Detector (z-score)
Scheduled every 60 seconds, runs after the threshold detector.
- For each active market with ≥ 20 snapshots in the last 60 minutes, compute the rolling mean and standard deviation of 1-minute returns.
- If the most recent return's z-score exceeds 3.0 AND 24h volume ≥ $50,000, fire a `warning` alert of type `statistical_anomaly`.
- Include the z-score, window size, and mean/stddev in the alert metadata.
- `DESIGN.md` should briefly note why this is complementary to the threshold detector (threshold catches large moves; z-score catches unusual moves relative to that market's own volatility).
- Unit test this heavily with synthetic price series: flat, trending, noisy, spiking.

### 3. Wallet Activity Detector
Scheduled every 60 seconds.
- For each watched wallet, fetch new trades since `lastSyncedAt`.
- Write to `wallet_trades` (idempotent on `txHash`).
- Fire `info` alert on any individual trade > $5,000.
- **Consensus signal**: after writing, query the `marketId-timestamp-index` GSI — if ≥ 3 distinct watched wallets traded the same market in the same direction within the last 30 minutes, fire a `critical` alert of type `consensus`.

### 4. News Correlation Detector
Runs as an SQS consumer on `news-to-process`.
- Extract keywords, score against active markets.
- If score ≥ 0.5 AND market volume ≥ $100,000, fire `warning` alert.
- Include article title, source, link, matched keywords in alert metadata.

## ntfy.sh Notification Worker

A Spring `@Component` that polls `alerts-to-notify` via long-polling SQS receive and POSTs to ntfy.sh.

```java
// severity → ntfy priority mapping
Map.of("info", "default", "warning", "high", "critical", "max")
```

Headers: `Title`, `Priority`, `Tags` (`type,severity`), `Click` (polymarket deep link). Body is the alert description. On success, flip `wasNotified = true` on the alert row and delete the SQS message. On failure, let SQS redeliver; after 5 failed attempts it goes to the DLQ.

## API Endpoints (Spring Web `@RestController`)

- `GET /` → serves `index.html` from `src/main/resources/static/`
- `GET /api/markets?category=&watchedOnly=&limit=`
- `GET /api/markets/{marketId}`
- `GET /api/markets/{marketId}/price-history?windowMinutes=60`
- `GET /api/markets/{marketId}/news`
- `GET /api/markets/{marketId}/whale-trades`
- `POST /api/markets/{marketId}/watch` — toggles `isWatched`
- `GET /api/wallets`
- `GET /api/wallets/{address}/trades?limit=50`
- `POST /api/wallets` — add
- `DELETE /api/wallets/{address}` — remove
- `GET /api/alerts?limit=100&severity=&type=&since=`
- `POST /api/alerts/{alertId}/mark-reviewed`
- `GET /api/stats` — system stats for the dashboard header
- `GET /actuator/health`
- `GET /actuator/prometheus`

Use Bean Validation (`@Valid`, `@NotBlank`, `@Pattern` for addresses) on request bodies. Return proper HTTP status codes. Use `@ControllerAdvice` for a global exception handler that returns RFC 7807 `application/problem+json` error responses.

## Dashboard UI

Single `index.html` file. Dark mode Tailwind via CDN. Chart.js via CDN. No build step.

### Header
Stats bar: markets tracked, alerts fired today, watched wallets, last poll time. ntfy.sh topic name with a copy button.

### Top: Live Alert Feed
Auto-refresh every 10s. Color-coded by severity. Each card: timestamp, type badge, title, description, market link, "mark reviewed" button. Soft ping via Web Audio API on new critical alerts while the tab is open.

### Middle: Watched Markets Grid
Cards per watched market: question, category, yes/no prices, 1-hour Chart.js line chart, 24h volume, whale trade count, news count. Click to expand. Un-watch button.

### Bottom: Smart Money Tracker
Table of watched wallets: alias, truncated address, category, last trade time, total trades, recent direction. Click for trade history. "Add wallet" form.

## Project Structure

```
polysign/
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── .env.example
├── .gitignore
├── .github/
│   └── workflows/
│       └── ci.yml
├── src/
│   ├── main/
│   │   ├── java/com/polysign/
│   │   │   ├── PolySignApplication.java
│   │   │   ├── config/
│   │   │   │   ├── AwsConfig.java          # DynamoDB, SQS, S3 clients (LocalStack-aware)
│   │   │   │   ├── DynamoConfig.java        # DynamoDbTable<T> beans
│   │   │   │   ├── HttpConfig.java          # WebClient + Resilience4j
│   │   │   │   ├── SchedulingConfig.java
│   │   │   │   └── BootstrapRunner.java     # Creates tables/queues/buckets on startup
│   │   │   ├── common/                      # CorrelationId, Clock wrapper, Result<T>
│   │   │   ├── model/                       # DynamoDB-annotated beans
│   │   │   │   ├── Market.java
│   │   │   │   ├── PriceSnapshot.java
│   │   │   │   ├── Article.java
│   │   │   │   ├── MarketNewsMatch.java
│   │   │   │   ├── WatchedWallet.java
│   │   │   │   ├── WalletTrade.java
│   │   │   │   └── Alert.java
│   │   │   ├── repository/                  # Thin wrappers over DynamoDbTable<T>
│   │   │   ├── polling/
│   │   │   │   ├── MarketPoller.java
│   │   │   │   ├── PricePoller.java
│   │   │   │   ├── WalletPoller.java
│   │   │   │   └── RssPoller.java
│   │   │   ├── detector/
│   │   │   │   ├── PriceMovementDetector.java
│   │   │   │   ├── StatisticalAnomalyDetector.java
│   │   │   │   ├── WalletActivityDetector.java
│   │   │   │   ├── ConsensusDetector.java
│   │   │   │   └── NewsCorrelationDetector.java
│   │   │   ├── processing/
│   │   │   │   ├── KeywordExtractor.java
│   │   │   │   ├── NewsMatcher.java
│   │   │   │   ├── NewsConsumer.java          # SQS listener
│   │   │   │   └── NotificationConsumer.java  # SQS listener → ntfy.sh
│   │   │   ├── alert/
│   │   │   │   ├── AlertService.java          # Idempotent alert creation
│   │   │   │   └── AlertIdFactory.java        # Deterministic ID hashing
│   │   │   ├── api/
│   │   │   │   ├── MarketController.java
│   │   │   │   ├── WalletController.java
│   │   │   │   ├── AlertController.java
│   │   │   │   ├── StatsController.java
│   │   │   │   └── GlobalExceptionHandler.java
│   │   │   └── metrics/
│   │   │       └── CustomMetrics.java
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-local.yml         # LocalStack endpoints
│   │       ├── application-aws.yml           # Real AWS profile
│   │       ├── logback-spring.xml             # JSON structured logging
│   │       ├── watched_wallets.json
│   │       └── static/
│   │           └── index.html
│   └── test/
│       └── java/com/polysign/
│           ├── detector/
│           │   ├── PriceMovementDetectorTest.java
│           │   ├── StatisticalAnomalyDetectorTest.java
│           │   └── ConsensusDetectorTest.java
│           ├── processing/
│           │   ├── KeywordExtractorTest.java
│           │   └── NewsMatcherTest.java
│           ├── alert/
│           │   └── AlertIdFactoryTest.java
│           └── integration/
│               └── PolySignIntegrationTest.java   # Testcontainers + LocalStack
├── DESIGN.md
└── README.md
```

## Docker Compose

Services: `localstack`, `polysign` (the Spring Boot app). A single JAR runs everything — schedulers, SQS consumers, and the web server — inside one process. Do NOT split into multiple services in compose; a monolithic Spring Boot deployment is more honest about what this is and easier to reason about. The boundaries between components are enforced in code, not in containers.

## Deployment Path to Real AWS (this is what takes it from a 7 to a 9)

Include a `deployment/` directory with:

1. **`deployment/aws-setup.md`** — a step-by-step guide for deploying to a real AWS account:
   - Create DynamoDB tables via AWS CLI commands (copy-pasteable).
   - Create SQS queues + DLQs via AWS CLI.
   - Create the S3 bucket.
   - Build the Docker image and push to ECR.
   - Run on ECS Fargate with a task definition (include a sample `task-definition.json`).
   - IAM policy JSON granting least-privilege access to exactly the tables/queues/bucket the app uses.
2. **`application-aws.yml`** profile that reads real AWS endpoints (no `endpointOverride`).
3. A section in the README titled **"Running on Real AWS"** that explains how to flip the profile via `SPRING_PROFILES_ACTIVE=aws`.

You do not need to actually deploy it — just make the path real and documented so a reviewer can see you've thought it through end to end.

## CI (GitHub Actions)

`.github/workflows/ci.yml`:
- Triggers on push and pull_request.
- Matrix on `java-version: [25]`.
- Steps: checkout, setup-java (Temurin), cache Maven, `mvn -B verify`.
- Integration tests run via Testcontainers (the runner has Docker available by default on `ubuntu-latest`).

A green CI badge goes at the top of the README.

## Testing Requirements (do not skip)

- **Unit tests** for every detector. For the statistical anomaly detector, test with synthetic series: flat (no alerts), linear trend (no alerts), random walk (no alerts), sudden spike (alert), gradual drift (no alert).
- **Unit test** for `AlertIdFactory` proving the same inputs produce the same ID and different inputs do not collide.
- **Unit tests** for `KeywordExtractor` and `NewsMatcher`.
- **Integration test** using Testcontainers LocalStack: spin up LocalStack, create tables/queues, insert fake price snapshots, run the price movement detector, assert an alert row is written AND an SQS message lands in `alerts-to-notify`. This one test alone is worth a lot in interviews — it proves the whole pipeline works.
- Target ≥ 70% line coverage on the `detector/`, `alert/`, and `processing/` packages. Don't chase coverage on config classes or DTOs.

## Build Order

### Phase 1: Foundation (~2.5 hours)
Maven project, Spring Boot skeleton, Docker + LocalStack (**pinned to `localstack/localstack:3.8`** — do not use `:latest`, it requires an auth token as of March 2026), `AwsConfig` + `DynamoConfig`, `BootstrapRunner` that creates all tables/queues/buckets on startup, `/actuator/health` green, JSON logging wired up. Verify with `aws --endpoint-url=http://localhost:4566 dynamodb list-tables` before moving on.

### Phase 2: Market + Price Polling (~2 hours)
Verify Polymarket endpoints with one live call first. `MarketPoller` writes markets, `PricePoller` writes `price_snapshots` every 60s with no-change dedupe. Let it run 10 minutes and confirm history accumulates.

### Phase 3: Alert Service + Price Movement Detector (~2 hours)
`AlertIdFactory` with unit tests first, then `AlertService` (idempotent writes), then `PriceMovementDetector` with thorough unit tests, then wire it into the scheduler and confirm it writes alerts and enqueues SQS messages.

### Phase 4: Notification Consumer + First End-to-End (~1 hour)
`NotificationConsumer` polling `alerts-to-notify`, posting to ntfy.sh. Install the ntfy app on your phone, subscribe, manually enqueue a test alert, confirm the notification arrives. **Do not move on until you have received a real notification on a real phone.**

### Phase 5: Statistical Anomaly Detector (~1.5 hours)
Rolling z-score implementation with heavy unit tests on synthetic series. Wire into scheduler. Tune so it does not spam on low-liquidity markets.

### Phase 6: Wallet Tracking + Consensus Detector (~2.5 hours)
Verify Polymarket data-api wallet endpoints first. `WalletPoller`, `WalletActivityDetector`, `ConsensusDetector` using the GSI. Unit tests for consensus logic with fake trades.

### Phase 7: News Ingestion + Correlation (~2 hours)
`RssPoller` with Rome, `KeywordExtractor`, `NewsMatcher`, `NewsConsumer` as an SQS listener, `NewsCorrelationDetector`.

### Phase 8: Dashboard (~2 hours)
Single `index.html`, three sections, auto-refresh, Chart.js mini charts, ntfy topic copy button.

### Phase 9: DLQs, Metrics, Resilience4j Polish (~1.5 hours)
Confirm every queue has a DLQ wired up. Confirm custom Micrometer metrics appear at `/actuator/prometheus`. Confirm Resilience4j wraps every outbound call and the circuit breaker actually opens when you point the app at a dead endpoint.

### Phase 10: Testcontainers Integration Test + CI (~1.5 hours)
One end-to-end integration test that exercises the whole price-movement pipeline through real LocalStack. GitHub Actions workflow running `mvn verify`. Green badge in the README.

### Phase 11: README + DESIGN.md (~1.5 hours)
See sections below. These documents are not an afterthought — they are the primary thing a reviewer will read before looking at code.

## README Must Include

- One-paragraph pitch framed as **"a real-time event-processing and anomaly-detection system"**, not as a "prediction market tool". Lead with the engineering, not the domain.
- **"Monitoring only, not a trading bot"** disclaimer in the first 10 lines.
- CI status badge.
- Architecture ASCII diagram showing pollers → DynamoDB → detectors → SQS → consumers → (DynamoDB writes + ntfy.sh).
- Feature list.
- AWS services table: every table, queue, and bucket, with a one-line justification for each. Include "why DynamoDB over RDS" and "why SQS over in-process queues" in one-sentence form, pointing to `DESIGN.md` for the long version.
- Setup: `docker-compose up --build`, open `http://localhost:8000`.
- ntfy.sh phone setup instructions.
- Configuring watched wallets.
- Tuning alert thresholds via `application.yml`.
- **"Running on Real AWS"** section pointing to `deployment/aws-setup.md`.
- Limitations section: monitoring only; keyword matching misses semantic matches; statistical detector needs more history on low-volume markets; consensus signal needs per-category tuning.
- Future work: embedding-based news matching, Kalshi integration, orderbook depth analysis, rewriting the detector pipeline as AWS Lambda functions behind EventBridge.

## DESIGN.md Must Include

This is the document that turns this project into interview fuel. Treat it as a mini system-design doc.

1. **Problem framing** — one paragraph.
2. **Data model** — why each DynamoDB table exists, what access patterns it serves, why the partition and sort keys are what they are, why the GSIs are what they are. Be explicit about access patterns in a table.
3. **Write path** — trace a single price poll from HTTP response → `PricePoller` → dedupe check → DynamoDB write → detector trigger → alert write → SQS enqueue → ntfy.sh POST. One paragraph per hop.
4. **Idempotency** — explain the deterministic alert ID scheme with a worked example showing how two runs of the same detector on the same window produce the same ID and only one row lands in DynamoDB.
5. **Failure modes and what happens** — Polymarket down, ntfy.sh down, DynamoDB throttles, SQS redelivery, worker crash mid-consume, DLQ behavior. For each: what the system does, what the user sees, how to recover.
6. **Why DynamoDB over RDS** — access patterns, TTL, scaling story, tradeoffs (no joins, GSI eventual consistency, item size limits).
7. **Why SQS over in-process queues** — crash isolation between producer and consumer, natural retry/DLQ, operational visibility via queue depth metrics.
8. **Why not Kafka** — operational overhead, single-writer-per-partition semantics not needed, SQS is strictly simpler for this scale.
9. **Scaling story** — what would change if this went from 200 markets to 20,000 and from 10 watched wallets to 10,000. What bottlenecks first (hint: Polymarket's rate limits, then price_snapshots write throughput). What you would do about each.
10. **What I would do differently on AWS proper** — Lambda + EventBridge for the detectors, CloudWatch alarms on the DLQ depth metrics, X-Ray for tracing, Secrets Manager for the ntfy topic.

Aim for 1,500–2,500 words. This is the document you will actually reread before your Amazon loop.

## Critical Implementation Notes

- **Verify Polymarket endpoints with live test requests at the start of phases 2 and 6.** Do not trust the URLs in this spec blindly.
- **No trading code, no signing libraries, no wallet write access.** This is a read-only public-data tool. Call this out explicitly in the README.
- **Every outbound HTTP call goes through Resilience4j.** No naked `WebClient` calls in pollers.
- **Structured JSON logging only.** Every log line has a `correlationId`, and that ID flows from poll → detector → alert → notification so you can grep one event through the whole pipeline.
- **`.gitignore`** excludes `.env`, `target/`, `.idea/`, `*.iml`, `localstack_data/`, and anything secret.
- **Commit in clean, reviewable chunks per phase.** The git log itself is a portfolio artifact.

## Non-Goals (explicit)

- No trading, ever.
- No embedding-based news matching in v2 (keyword matching only — the statistical anomaly detector is the "smart" layer for v2).
- No user accounts, no multi-tenant support, no auth on the API (it runs locally or behind a VPN).
- No frontend framework. The dashboard is one HTML file. Fighting webpack is not the point of this project.

Build it incrementally. Verify each phase before moving on. Do not skip the Testcontainers integration test in phase 10 — it is the single most valuable test in the project.
