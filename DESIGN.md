# PolySign — Design Document

## Problem Framing

Prediction markets produce continuous price data, and some price movements mean something. The hard part isn't detecting movements — it's knowing which ones are signal and which are noise, delivering the signal ones to a phone without crying wolf, and then measuring whether the system's definition of "signal" was actually predictive. PolySign does all three.

## Data Model

Every table was designed around a specific access pattern. There are no tables that exist "because the entity exists" — each one maps to a query the system actually runs.

| Table | PK | SK | Access pattern | Why this key design |
|---|---|---|---|---|
| `markets` | `marketId` | — | Single-key get by ID; full scan for detector loop; category GSI for filtered API queries | PK-only because markets are always fetched individually or scanned in bulk |
| `price_snapshots` | `marketId` | `timestamp` (ISO-8601) | Range query: "all snapshots for market X in the last 60 minutes" | Composite key enables efficient time-range queries per market. 30-day TTL auto-cleans old data. |
| `articles` | `articleId` (SHA-256 of URL) | — | Single-key get for dedup check; scan for recent articles | SHA-256 of URL makes article dedup a single conditional write |
| `market_news_matches` | `marketId` | `articleId` | "All news matches for market X" for the dashboard | Denormalized `articleTitle` + `articleUrl` avoid N+1 lookups |
| `watched_wallets` | `address` | — | Full scan (10 rows); single-key get on poll | Small table, full scan is cheaper than a GSI |
| `wallet_trades` | `address` | `txHash` | Per-wallet trade history; `marketId-timestamp-index` GSI for consensus window query | SK=`txHash` gives natural idempotency — same trade re-processed is an overwrite with identical data |
| `alerts` | `alertId` (SHA-256) | `createdAt` | Conditional write for idempotency; `marketId-createdAt-index` GSI for per-market queries and convergence checks | Deterministic PK + deterministic SK = same DynamoDB slot on every write attempt. 30-day TTL. |
| `alert_outcomes` | `alertId` | `horizon` | Conditional write per (alert, horizon) pair; `type-firedAt-index` GSI for per-detector aggregation | One row per alert per horizon. No TTL — outcomes are the whole point. |

### Why DynamoDB over RDS

Every query PolySign runs is either a single-key get or a range query on a known GSI. No joins. No transactions across tables. No aggregations that wouldn't fit in application code. This is the access-pattern profile where DynamoDB is the obvious choice.

Specific advantages I rely on:

- **TTL**: `price_snapshots` and `alerts` expire after 30 days automatically. No cron job to clean up. No `DELETE FROM ... WHERE created_at < NOW() - INTERVAL '30 days'` blocking the write path.
- **Per-table capacity**: `price_snapshots` takes 400 writes per 60-second cycle. `watched_wallets` takes 0 writes per hour. Paying for each table's actual throughput instead of one RDS instance that's sized for the hottest table is cheaper and simpler.
- **Conditional writes**: `attribute_not_exists(alertId)` on the composite key gives me exactly-once alert writes without a separate lock table or transaction. This is the foundation of the idempotency model.

Tradeoffs I accept:

- No ad-hoc queries. If I want to ask "how many alerts fired per category last week," I have to scan and filter in application code. RDS would give me this for free with SQL.
- Eventual consistency on GSIs. A write to `alerts` is immediately visible on the base table but may take milliseconds to propagate to `marketId-createdAt-index`. The PhoneWorthinessFilter's convergence check could theoretically miss a just-written alert. In practice, the 15-minute convergence window makes this irrelevant.
- 400KB item limit. Alert metadata maps can't grow unbounded. I keep metadata flat and small.

## Write Path

Tracing one alert from source to phone, one paragraph per hop:

**Gamma API → MarketPoller → `markets`**: Every 60 seconds, `MarketPoller` paginates the Gamma API (`?active=true&closed=false&limit=200`), applies quality filters (lifetime volume >= $10k, 24h volume >= $10k, > 12h to expiry), sorts by 24h volume, caps at 400, and upserts each market. The cap is deterministic — same input produces the same 400 markets.

**CLOB API → PricePoller → `price_snapshots`**: For each market in DynamoDB, `PricePoller` fetches the midpoint from `clob.polymarket.com/midpoint?token_id=...` through the rate limiter (10/sec). If the price hasn't changed since the last snapshot (compared at 4 decimal places), the write is skipped. Otherwise, a `PriceSnapshot` is written with `expiresAt` set 30 days out.

**`price_snapshots` → PriceMovementDetector → `alerts`**: Every 60 seconds, the detector queries the last 60 minutes of snapshots per market, finds the maximum absolute move within any 15-minute window, and checks it against the threshold (>= 8%), the delta-p floor (>= 0.03), the extreme-zone filter, and the volume floor ($50k). If it passes, `AlertIdFactory.generate()` computes a deterministic ID, and `AlertService.tryCreate()` writes to `alerts` with `attribute_not_exists(alertId)`.

**`alerts` → SQS → NotificationConsumer → ntfy.sh**: `AlertService` enqueues the `alertId` to `alerts-to-notify`. `NotificationConsumer` long-polls the queue (20-second server-side wait, 10 messages per batch). For each message: fetch the alert from DynamoDB, evaluate `PhoneWorthinessFilter`, persist `phoneWorthy` on the alert row. If worthy: POST to `ntfy.sh/{topic}` with severity-mapped priority, mark `wasNotified=true`, delete the SQS message. If the POST fails: leave the message in the queue for SQS to redeliver (up to 5 times before DLQ).

## Alert ID Design

Alert IDs are `SHA-256(type | marketId | bucketedTimestamp | payloadHash)`. The bucketed timestamp is the detection instant rounded down to the nearest dedupe-window boundary in epoch seconds. For a 30-minute window: `(epochSeconds / 1800) * 1800`. Events within the same 30-minute window for the same market and type produce the same hash.

The `bucketedInstant()` function also produces the alert's `createdAt` (sort key). This is critical and I'll explain why through the bug I hit.

### The composite-key bug

The `alerts` table has a composite key: PK=`alertId`, SK=`createdAt`. My first implementation set `createdAt = clock.now()`. The conditional write `attribute_not_exists(alertId)` checks a specific (PK, SK) slot. Because `createdAt` changed every cycle, each write targeted a *new* slot that didn't exist yet, so the condition always passed. Three detector cycles produced three duplicate alerts.

The fix: `createdAt` is set to `AlertIdFactory.bucketedInstant(now, dedupeWindow)`, not the wall clock. Same detection window, same inputs → same `alertId` + same `createdAt` → same DynamoDB slot → condition rejects the duplicate.

### Worked example

Inputs: type=`price_movement`, marketId=`test-idem-001`, timestamp=`2026-04-09T07:14:22Z`, dedupeWindow=30min.

Bucketed timestamp: `(1744182862 / 1800) * 1800 = 1744182000` → `2026-04-09T07:00:00Z`.

SHA-256 input: `price_movement|test-idem-001|1744182000|`

Alert ID: `845f165cde7828da5dd7e7cc649d3f225f783467d500bd0279809e8f1ef9b048`

Any detection between 07:00:00 and 07:29:59 UTC for the same market and type produces this exact ID and targets this exact DynamoDB slot.

### Why not UUIDs

`UUID.randomUUID()` would be simpler. But every UUID is unique by definition, so `attribute_not_exists` would never reject. You'd need a separate dedup layer — either a conditional write on a different attribute, or a dedup table, or a time-windowed cache. Deterministic IDs make idempotency a property of the ID itself rather than an external mechanism.

### Bypass mode

When a move exceeds 2x the threshold (>= 16%), the detector passes `Duration.ZERO` as the dedupe window. This disables bucketing — the raw epoch second becomes the bucket. A second extreme move 10 seconds later gets its own alert. This is intentional: if a market moves 20% in a minute, I want to know about each leg.

## SQS Architecture

### The consumer pattern

`NotificationConsumer` is the reference SQS consumer. The pattern:

1. **Lazy queue URL**: The queue URL is resolved on first poll, not in the constructor. `BootstrapRunner` (which creates queues) runs after bean wiring, so the constructor can't call `getQueueUrl()`. The resolved URL is cached in a `volatile` field.
2. **Long poll**: `waitTimeSeconds=20` on `ReceiveMessageRequest`. The thread blocks server-side when the queue is empty instead of spinning. `@Scheduled(fixedDelay=1000)` adds a 1-second gap between poll completions to prevent a tight loop when messages flow continuously.
3. **Delete on success**: The SQS message is deleted only after ntfy.sh returns HTTP 200. A failed POST leaves the message invisible until the visibility timeout expires, then SQS redelivers it.
4. **DLQ after 5 retries**: `maxReceiveCount=5` on the redrive policy. After 5 delivery attempts, the message moves to `alerts-to-notify-dlq`. The DLQ depth gauge alerts me that something is wrong.
5. **Stale message handling**: If the alert ID from the message body doesn't resolve to a DynamoDB row (TTL expired, or the alert was never written), delete the message immediately. Don't let a stale reference clog the queue.

### Why SQS over in-process queues

If the Spring Boot process dies between `alertService.tryCreate()` and the ntfy.sh POST, an in-memory `BlockingQueue` loses the message. The alert exists in DynamoDB but the notification never fires. SQS persists the message across process restarts.

Also: queue depth metrics. `polysign.sqs.queue.depth` tells me the consumer is falling behind. `polysign.dlq.depth` tells me something is broken. An in-memory queue gives me neither without manual instrumentation.

### Why not Kafka

Kafka gives me ordering, replay, and multi-consumer fan-out. I don't need any of these. PolySign has one producer and one consumer per queue. Messages don't need ordering — alerts are independent events. Replay isn't useful because alerts are already persisted in DynamoDB.

Kafka also requires a broker cluster. SQS is serverless, zero-maintenance, and effectively free at this message volume (< 1M messages/month = $0.40). The operational overhead of Kafka isn't justified by any requirement this system has.

## Resilience4j Strategy

Six outbound call sites, each wrapped in a circuit breaker + retry. One rate limiter.

| Call site | CB instance | Retry | Rate limiter | Notes |
|---|---|---|---|---|
| MarketPoller → Gamma API | `polymarket-gamma` | 3 attempts, exp backoff 1s–10s | — | Pagination: per-page CB evaluation |
| PricePoller → CLOB /midpoint | `polymarket-clob` | 3 attempts, exp backoff 500ms–8s | 10 calls/sec | Per-market catch prevents one 5xx from crashing the scan |
| WalletPoller → Data API | `polymarket-data` | 3 attempts, exp backoff 1s–10s | — | Per-wallet catch |
| OrderbookService → CLOB /book | `polymarket-clob` | None (500ms budget) | Shared with PricePoller | One shot — retry doesn't fit in 500ms. Alert fires with null book fields on failure. |
| NotificationConsumer → ntfy.sh | `ntfy` | 3 attempts, exp backoff 2s–15s | — | CB window is 10 calls (smaller — ntfy traffic is lower) |
| RssPoller → RSS feeds | `rss-news` | 3 attempts, exp backoff 2s–10s | — | Uses `java.net.http.HttpClient`, not WebClient (Rome needs a blocking `InputStream`). Still CB + retry wrapped. |

Circuit breaker config: 20-call sliding window (10 for ntfy), 50% failure threshold, 30-second open state, auto half-open. When a breaker opens, the caller gets a `CallNotPermittedException` that the per-item catch swallows. The next half-open probe fires 30 seconds later.

The `rss-news` exception is intentional. Rome's `SyndFeedInput.build()` takes an `InputStream`. WebClient is reactive and doesn't expose one without `.block()` gymnastics that defeat the purpose. Using `java.net.http.HttpClient` directly is simpler and still gets the same resilience wrapping — it's just imperative instead of reactive.

## Failure Modes

| What breaks | What the system does | What the user sees | Recovery |
|---|---|---|---|
| Polymarket Gamma API down | CB opens after 10 failures in 20 calls. Market list goes stale. | Dashboard shows old markets. New markets aren't discovered. | CB half-opens after 30s and probes automatically. |
| Polymarket CLOB down | CB opens. Prices go stale. Detectors run on old data. | No new price alerts. Stat detector keeps evaluating old snapshots (correctly: no new return = no anomaly). | Auto-recovery on half-open. |
| ntfy.sh down | POST fails. SQS message stays in queue. After 5 attempts → DLQ. | No phone notification. Alert still in DynamoDB and on dashboard. DLQ depth alarm fires. | Fix ntfy or wait for recovery. Manually redrive DLQ messages. |
| DynamoDB throttle | SDK retries with backoff. Some snapshot writes may be dropped. | Possible gaps in price history. Detectors may miss moves that span the gap. | Increase table capacity or switch to auto-scaling. |
| Worker crash mid-consume | SQS visibility timeout expires (default 30s). Message becomes visible. Next poll picks it up. | Delayed notification, but no lost messages. | Automatic. |
| Poison message in SQS | Message exceeds maxReceiveCount (5). Moves to DLQ. Pipeline continues. | One alert never notifies. DLQ depth alarm fires. | Investigate the DLQ message body, fix the root cause, redrive. |

## Signal Quality Methodology

### What precision means here

Precision for a detector at a given horizon is: of the alerts where the detector predicted a direction (up or down) and the market moved enough to count, what fraction got the direction right?

```
precision = correct / (correct + wrong)
```

This is not accuracy. Accuracy would include every alert in the denominator, including flat outcomes where the market didn't move. That penalizes the detector for the market being boring — not useful. Precision only counts cases where the market actually moved, and asks: when it moved, did the detector call the direction?

It's also not recall. I don't measure how many real moves the system *missed*. I can't — I don't have a ground-truth label for "this market moved and PolySign should have caught it." Precision is the measurable thing: of the signals I fired, which ones were right?

### The 0.5 percentage point dead zone

If the price at T+horizon is within 0.5 percentage points of the price at alert time, I call it "flat" and exclude it from both numerator and denominator. The rationale: a market at 0.50 that's at 0.503 an hour later didn't prove the alert right or wrong. It proved nothing. Including it would add noise to the precision number without adding information.

The threshold of 0.5pp (0.005 in decimal) is a judgment call. Lower and you reward random microstructure noise. Higher and you throw away too many real data points. 0.5pp is roughly one tick on a mid-range Polymarket market.

### Horizon-based vs resolution-based evaluation

**Horizon-based** (t15m, t1h, t24h): measures short-term predictive power. "When I said something was happening, did the price keep moving that direction?" This is what's actionable — if the t15m precision for price_movement is 65%, I have 15 minutes to look at the market before the information decays.

**Resolution-based**: measures against the final binary outcome (market resolves to 0 or 1). Higher stakes, cleaner ground truth, but only available after the market closes. Useful for long-term evaluation of whether PolySign's signals correlate with the right answer, not just the right direction of movement.

### Known biases

**Survivor bias**: Resolution evaluation only covers markets that closed during the measurement period. Markets that stay open for months don't get scored. If PolySign is better at detecting moves on short-duration markets (which resolve frequently), the resolution precision will be overstated for the general case.

**Lookback bias**: Snapshots are written every 60 seconds. The "closest snapshot to T+1h" might actually be from T+59min or T+61min. At the t15m horizon, a 2-minute tolerance window means the evaluated price could be from T+13min to T+17min. This adds noise but not systematic bias.

**Clustering**: One major news event can trigger 10+ correlated alerts across related markets. Each gets scored independently, inflating the apparent sample size. In reality, those 10 alerts aren't 10 independent observations — they're one observation counted 10 times. I don't currently correct for this. A proper fix would cluster alerts by temporal proximity and score each cluster once.

### The delta-p floor — where product judgment meets statistics

A market at 0.0045 ticked to 0.0055 and fired a "22% price movement" alert. Mathematically valid. Practically worthless — a 1-basis-point shift on a 99.5%-likely market is noise. The percentage threshold couldn't see this because it doesn't know about probability substance.

I added a minimum absolute delta of 3 percentage points. If `|toPrice - fromPrice| < 0.03`, the alert doesn't fire regardless of the percentage move. Combined with the extreme-zone filter (skip if both prices < 0.05 or > 0.95), this killed the tail-zone spam: hundreds of alerts per minute dropped to ~5/minute on moves with real probability substance.

This is the design decision I'm proudest of because it's not in any textbook. It required looking at the alerts the system was producing and asking "would I actually want my phone to ring for this?" — then encoding the answer into a filter.

## Detector Architecture

### The testable subclass pattern

Every detector needs DynamoDB access to query snapshots and trades. In unit tests, I don't want a real DynamoDB. But I also don't want to mock `DynamoDbTable<PriceSnapshot>` and its fluent query API — that's 4+ mock setups per call and the tests become harder to read than the code.

Instead, methods that touch DynamoDB (like `querySnapshots()` in `PriceMovementDetector`) are package-private. Tests in the same package define a `TestableDetector extends PriceMovementDetector` that overrides those methods to return canned data. The detector's actual logic — the threshold check, the delta-p floor, the z-score computation — runs against real data without touching DynamoDB.

### The two-constructor pattern

`ConsensusDetector` has two constructors: a public one annotated `@Autowired` that Spring uses for dependency injection, and a package-private one that tests call directly with mocks. This avoids the fragility of `@InjectMocks` while keeping the Spring wiring clean.

### AppClock for deterministic testing

All time-dependent code calls `AppClock.now()` instead of `Instant.now()`. `AppClock` wraps a `java.time.Clock` that defaults to `Clock.systemUTC()` in production.

In tests, `AppClock.setClock(Clock.fixed(T0, ZoneOffset.UTC))` freezes time. The golden-path integration test uses this to:

1. Set clock to T0 (2 hours ago), run `PriceMovementDetector.detect()`, assert the alert's `createdAt` matches the bucketed T0.
2. Advance clock to T1 (T0 + 20 minutes), run `AlertOutcomeEvaluator.evaluate()`, assert the outcome row's `evaluatedAt` matches T1.

Without this, the test would be non-deterministic — the evaluator might consider the alert "not yet due" depending on when the test runs relative to wall-clock time.

## The Scaling Story

Current scale: 400 markets, 10 wallets, ~5 alerts/minute, one Spring Boot process. Here's what breaks first on the way to 20,000 markets and 10,000 wallets.

### Bottleneck 1: Polymarket rate limits

The CLOB API rate limiter is set to 10 calls/sec. At 400 markets, one price poll cycle takes ~40 seconds. At 20,000 markets: 33 minutes — longer than the 60-second interval. The system can't keep up.

**Fix**: Negotiate higher rate limits. Or parallelize across multiple API keys. Or, if Polymarket adds batch endpoints, use them. The rate limiter configuration in `application.yml` makes this a config change, not a code change.

### Bottleneck 2: price_snapshots write throughput

20,000 markets x 1 snapshot per cycle = 20,000 writes per minute = 333 sustained WCU. DynamoDB on-demand handles this, but cost scales linearly (~$50/month just for snapshots). With provisioned capacity, auto-scaling with target tracking at 70% would keep costs lower.

**Fix**: `BatchWriteItem` (25 items per batch) reduces API calls by 25x. Or partition the table by date so writes distribute across partitions.

### Bottleneck 3: detector fan-out

Each detector scans all markets every 60 seconds. At 20,000 markets, each scan pulls 20,000 market rows + up to 60 snapshots per market. This is a lot of read capacity.

**Fix**: DynamoDB Streams. Instead of scanning all markets, detectors react to new snapshots via stream events. A Lambda triggered by `price_snapshots` inserts would evaluate only the market that just got a new data point. Scan cost drops from O(markets) to O(1) per evaluation.

### Bottleneck 4: wallet polling

At 10,000 wallets, `WalletPoller` can't cycle through them all in 60 seconds even without rate limits — the Data API calls add up.

**Fix**: Partition wallets across multiple poller instances. Or move consensus detection to a DynamoDB Streams trigger on `wallet_trades`, so consensus evaluates reactively when a new trade lands rather than polling for it.

## What I Would Do Differently on AWS

See the [README](README.md#what-i-would-do-differently-on-real-aws) for the full treatment. In brief: Lambda + EventBridge for each detector, Step Functions with `Wait` states for the outcome evaluator, Kinesis for ordered wallet trade processing, X-Ray with adaptive sampling, DynamoDB auto-scaling, and CloudWatch alarm thresholds derived from the signal quality metrics.

The core insight: every `@Scheduled` method in PolySign maps cleanly to a Lambda + EventBridge rule. The monolith design is right for local dev and portfolio demos. For production at scale, the individual components are already isolated enough in code to split into separate deployments with minimal refactoring.

## Operational Runbook

Three chaos experiments verify the failure paths described above:

1. **Circuit breaker under blocked egress**: block outbound traffic to `polymarket.com` with `iptables -I OUTPUT -d polymarket.com -j DROP`. The Gamma and CLOB circuit breakers open within 10 failures (sliding window 20, 50% threshold). `WalletPoller` stops fetching. All `@Scheduled` pollers log `WARN` and continue the loop — no crash. Restore traffic: breakers transition to half-open within 30 seconds, probes succeed, breakers close. Normal polling resumes.

2. **DLQ alarm on ntfy.sh failure**: simulate ntfy.sh returning 500 by pointing `ntfy.base-url` at a local 500 endpoint. After 5 delivery attempts, the SQS message moves to the DLQ. The `polysign.dlq.depth` gauge increments to 1, triggering the CloudWatch alarm. The pipeline continues processing other alerts normally. Fix: redrive the DLQ message after restoring the endpoint.

3. **Idempotent restart mid-enqueue**: kill the process immediately after a `PriceMovementDetector` alert write but before the SQS enqueue commits. Restart. The detector re-fires the same event — same deterministic `alertId` + `createdAt` → `attribute_not_exists` condition rejects the duplicate write → alert count stays at 1 → SQS enqueue proceeds once. Zero duplicate notifications.
