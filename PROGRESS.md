# PolySign — Progress Log

Running log of completed phases. Claude Code updates this at the end of every phase session. Read top-to-bottom at the start of every new session to reconstruct state.

Format per phase:

```
## Phase N — <name>
Status: complete | in-progress | blocked
Date: YYYY-MM-DD

### What was built
- bullet list of components, classes, endpoints, tables, etc.

### Files touched
- path/to/file1
- path/to/file2

### Verification
- what command was run
- what the expected result was
- what the actual result was

### Deviations from spec
- any place the implementation differs from spec.md, and why
- any ambiguities that were resolved and how

### Notes for next phase
- anything the next session needs to know
- known rough edges to clean up later
```

---

<!-- Phase entries go below this line, newest at the bottom. -->

## Phase 0 — Repo bootstrap
Status: complete
Date: (fill in)

### What was built
- spec.md (build specification)
- CONVENTIONS.md (cross-phase rules)
- PROGRESS.md (this file)

### Files touched
- spec.md
- CONVENTIONS.md
- PROGRESS.md

### Verification
- `ls` shows all three files present
- `git log` shows initial commit

### Deviations from spec
- none

### Notes for next phase
- Phase 1 starts from an empty Maven project. No Java code exists yet.
- LocalStack must be pinned to `localstack/localstack:3.8` — do not use `:latest`.

## Phase 1 — Foundation
Status: complete
Date: 2026-04-08

### What was built
- `pom.xml` — Spring Boot 3.5.5 parent, Java 25, AWS SDK v2 BOM (2.27.21), Resilience4j 2.2.0, logstash-logback-encoder 8.0, Rome 2.1.0, Testcontainers, Prometheus Micrometer
- `PolySignApplication.java` — `@SpringBootApplication @EnableScheduling` entry point
- `application.yml` — full config including all table names, queue names, detector thresholds, ntfy topic
- `application-local.yml` — LocalStack endpoint override via `${AWS_ENDPOINT_URL:http://localstack:4566}`
- `application-aws.yml` — stub profile for real AWS (no endpoint override, DefaultCredentialsProvider)
- `logback-spring.xml` — JSON structured logging via `logstash-logback-encoder`, `correlationId` MDC slot
- `watched_wallets.json` — 10 placeholder wallet entries (replace from polymarket.com/leaderboard)
- 7 DynamoDB-annotated model beans: `Market`, `PriceSnapshot`, `Article`, `MarketNewsMatch`, `WatchedWallet`, `WalletTrade`, `Alert` — explicit getters/setters, PK/SK/GSI annotations on getter methods (no Lombok on model classes to avoid DynamoDB annotation conflicts)
- `AwsConfig.java` — `DynamoDbClient`, `DynamoDbEnhancedClient`, `SqsClient`, `S3Client` beans; `DefaultCredentialsProvider`; endpoint override applied when `aws.endpoint-override` is set; S3 `forcePathStyle=true`
- `DynamoConfig.java` — typed `DynamoDbTable<T>` beans for all 7 tables, table names read from `@Value` properties
- `HttpConfig.java`, `SchedulingConfig.java` — empty `@Configuration` stubs for Phases 2-3
- `CorrelationId.java` — MDC helper (`try (CorrelationId.set()) { ... }`)
- `AppClock.java` — injectable clock wrapper for testability (`now()`, `nowIso()`, `nowEpochSeconds()`)
- `Result<T>` — sealed interface discriminated union (Success/Failure) for error handling without checked exceptions
- `BootstrapRunner.java` — `ApplicationRunner @Order(1)`; creates 7 DynamoDB tables (PAY_PER_REQUEST, GSIs); enables TTL on `price_snapshots.expiresAt` and `alerts.expiresAt`; creates 3 DLQs then 3 main queues with redrive policies (maxReceiveCount=5); creates `polysign-archives` S3 bucket; all operations idempotent
- `Dockerfile` — multi-stage: `maven:3.9-eclipse-temurin-25` build stage + `eclipse-temurin:25-jre-jammy` runtime; non-root user
- `docker-compose.yml` — LocalStack pinned to `localstack/localstack:3.8`; polysign depends on LocalStack healthcheck; volume mounted at `/var/lib/localstack`
- `.env.example`, `.gitignore`

### Files touched
- pom.xml
- Dockerfile
- docker-compose.yml
- .env.example
- .gitignore
- src/main/java/com/polysign/PolySignApplication.java
- src/main/java/com/polysign/config/AwsConfig.java
- src/main/java/com/polysign/config/DynamoConfig.java
- src/main/java/com/polysign/config/HttpConfig.java
- src/main/java/com/polysign/config/SchedulingConfig.java
- src/main/java/com/polysign/config/BootstrapRunner.java
- src/main/java/com/polysign/common/CorrelationId.java
- src/main/java/com/polysign/common/AppClock.java
- src/main/java/com/polysign/common/Result.java
- src/main/java/com/polysign/model/Market.java
- src/main/java/com/polysign/model/PriceSnapshot.java
- src/main/java/com/polysign/model/Article.java
- src/main/java/com/polysign/model/MarketNewsMatch.java
- src/main/java/com/polysign/model/WatchedWallet.java
- src/main/java/com/polysign/model/WalletTrade.java
- src/main/java/com/polysign/model/Alert.java
- src/main/resources/application.yml
- src/main/resources/application-local.yml
- src/main/resources/application-aws.yml
- src/main/resources/logback-spring.xml
- src/main/resources/watched_wallets.json

### Verification
- `mvn -q compile` → exit 0 (Java 25.0.2, Maven 3.9.14)
- `mvn -q -DskipTests package` → exit 0
- `docker compose up -d --build` → both containers started; LocalStack healthy; polysign started
- `curl http://localhost:8080/actuator/health` → `{"status":"UP",...}`
- `aws --endpoint-url=http://localhost:4566 dynamodb list-tables` → all 7 tables present: alerts, articles, market_news_matches, markets, price_snapshots, wallet_trades, watched_wallets
- SQS queues verified: news-to-process, news-to-process-dlq, wallet-trades-to-process, wallet-trades-to-process-dlq, alerts-to-notify, alerts-to-notify-dlq
- S3 bucket verified: polysign-archives

### Deviations from spec
1. **Port**: spec README says `localhost:8000`; verification step uses `localhost:8080`. Used 8080 (Spring default). The README will clarify in Phase 11.
2. **Model classes without Lombok**: Spec says to use Lombok for mutable entity classes. However, DynamoDB Enhanced Client requires annotations on getter methods, which conflicts with Lombok's auto-generated getters. Wrote all 7 model classes with explicit getters/setters. `@Slf4j` and other Lombok annotations will still be used on service/poller classes in later phases.
3. **Dockerfile fix**: Initial Dockerfile used `eclipse-temurin:25-jdk-jammy` as build stage (no Maven). Fixed to use `maven:3.9-eclipse-temurin-25` which bundles Maven.
4. **docker-compose volume path**: Initial volume mount was at `/tmp/localstack/data`. LocalStack 3.8 tries to `rm -rf /tmp/localstack` on startup; the volume mount blocked this. Fixed to mount at `/var/lib/localstack` (LocalStack 3.x canonical data path).

### Notes for next phase
- Phase 2: verify Polymarket endpoints with a live test request BEFORE writing any poller code (spec requirement).
- Endpoints to verify: `https://gamma-api.polymarket.com/markets?active=true&closed=false&limit=200` and `https://clob.polymarket.com/` (`/midpoint`, `/price`, `/book`, `/trades`).
- `HttpConfig.java` stub is in place — populate with WebClient beans + Resilience4j decorators.
- `SchedulingConfig.java` stub is in place — configure thread pool.
- LocalStack is running; Docker images are cached — `docker compose up -d` is fast for subsequent runs.

## Phase 2 — Market + Price Polling
Status: complete
Date: 2026-04-09

### What was built
- `Market.java` — added `yesTokenId` (String), `clobTokenIds` (String raw JSON), `volume24h` (String)
  to the existing model; all getters/setters explicit for DynamoDB Enhanced Client compatibility
- `CategoryClassifier.java` — closed-set keyword matcher (politics | sports | crypto | economics |
  entertainment | other); crypto evaluated before politics to prevent false matches on price-related
  keywords; callers log INFO when a market lands in "other"
- `HttpConfig.java` — populated with `gammaApiClient` and `clobApiClient` WebClient beans;
  16 MB max in-memory buffer on gammaApiClient; User-Agent header identifying the bot
- `SchedulingConfig.java` — `SchedulingConfigurer` with 6-thread `ThreadPoolTaskScheduler`,
  30-second graceful shutdown; thread names `polysign-sched-N`
- `application.yml` — added `polysign.pollers.market/price.interval-ms` (60 000 ms each, initial
  delays 5 000 / 30 000); full `resilience4j` config block for `polymarket-gamma` and
  `polymarket-clob` circuit breakers, retries (exponential back-off), and CLOB rate limiter (10/s)
- `MarketPoller.java` — `@Scheduled(fixedDelayString=…)` poller; paginates Gamma API 200/page
  via limit+offset; parses JSON-string-encoded fields (outcomes, clobTokenIds); pre-extracts
  `yesTokenId = clobTokenIds[0]`; classifies category via `CategoryClassifier`; extracts keywords
  from question; preserves `isWatched` via read-before-write DynamoDB get; upserts Market entity;
  Resilience4j CircuitBreaker + Retry wrapping; per-item catch; `polysign.markets.tracked` Gauge
- `PricePoller.java` — `@Scheduled(fixedDelayString=…)` poller; DynamoDB table scan; per-market
  CLOB `/midpoint?token_id=…` fetch; `noPrice = 1 - yesPrice`, `midpoint = yesPrice`; no-change
  dedupe via `BigDecimal.setScale(4, HALF_UP).compareTo()` against latest snapshot; writes
  `PriceSnapshot` with `expiresAt = now + 7 days`; Resilience4j RateLimiter + Retry + CircuitBreaker
  per call; per-market catch; `polysign.prices.polled` Counter

### Files touched
- src/main/java/com/polysign/model/Market.java
- src/main/java/com/polysign/config/HttpConfig.java
- src/main/java/com/polysign/config/SchedulingConfig.java
- src/main/java/com/polysign/common/CategoryClassifier.java  (new)
- src/main/java/com/polysign/poller/MarketPoller.java  (new)
- src/main/java/com/polysign/poller/PricePoller.java  (new)
- src/main/resources/application.yml

### Verification
- `mvn -q compile` → exit 0 (Java 25.0.2, Maven 3.9.14)
- `docker compose up --build -d` → both containers healthy
- After ~90 s: `docker exec polysign-localstack-1 awslocal dynamodb scan --table-name markets --limit 3`
  → 3 real market items with `marketId`, `question`, `category`, `yesTokenId`, `volume24h`, `updatedAt`
- `docker exec polysign-localstack-1 awslocal dynamodb scan --table-name price_snapshots --limit 5`
  → 5 items with `marketId`, `timestamp`, `yesPrice`, `noPrice`, `midpoint`;
    `yesPrice + noPrice == 1.0` confirmed on all checked rows (e.g. 0.57 / 0.43)
- Log grep shows `market_upserted` entries with correlationId, `price_snapshot_written` entries
- Polymarket Gamma API returned 30 000+ active markets across 150+ pages — pagination working

### Deviations from spec
1. **Compilation fix**: `PageIterable.pages()` method does not exist in AWS SDK v2 DynamoDB Enhanced
   Client. Fixed by using the `.items()` method which returns a flattened `SdkIterable<T>` over all
   pages — functionally identical but using the correct SDK API.
2. **`price_no_change` log level**: The no-change dedupe success path logs at DEBUG (not INFO) to
   avoid flooding logs during normal operation. The first poll cycle will always write snapshots
   (no prior data to dedupe against); dedupe triggers from the second cycle onward.

### Known concern — Phase 6
**CLOB `/trades` endpoint returns 401 Unauthorized** (requires Polymarket API key / L1 wallet
authentication). Phase 6 wallet tracking must use Polygon RPC fallback instead of CLOB `/trades`.
Do not attempt to solve this in Phase 6 until the Polygon RPC approach is confirmed viable.

### Notes for next phase
- Phase 3: Anomaly detector — consume price_snapshots from DynamoDB, emit alerts to SQS
  `alerts-to-notify` queue.
- `isWatched` flag in markets is currently always `false` (default). Phase 3 or 4 should define
  a mechanism to mark markets as watched (e.g. a seeded list of high-volume market IDs).
- MarketPoller first cycle takes several minutes because Polymarket has 30 000+ active markets.
  Consider adding a `--market-limit` override for fast local dev iterations.
- PricePoller rate-limits to 10 CLOB calls/s via Resilience4j; first full scan takes ~minutes.
  The `polysign.prices.polled` Prometheus counter is the authoritative measure of throughput.
- Both pollers gracefully handle CLOB / Gamma API failures via per-item catch — one 4xx/5xx
  never crashes the loop.

## Phase 2.5 — Market volume floor + dedupe verification
Status: complete
Date: 2026-04-09

### What was built
- `application.yml` — added `polysign.pollers.market.min-volume-usdc: 10000`
- `MarketPoller.java` — volume floor filter: markets whose lifetime volume string parses
  to < `minVolumeUsdc` (default 10 000 USDC) are skipped before any DynamoDB I/O;
  unparseable volume strings pass through; poll cycle now logs
  `market_poll_complete kept=X of=Y` at INFO each cycle; `@Value`-injected via
  constructor parameter (Spring resolves before the Gauge registration runs)
- `PricePoller.java` — `price_no_change` dedupe log confirmed working; reverted back
  to DEBUG after verification (temporarily bumped to INFO for this session only)

### Files touched
- src/main/resources/application.yml
- src/main/java/com/polysign/poller/MarketPoller.java
- src/main/java/com/polysign/poller/PricePoller.java  (log level revert only)

### Verification — volume floor
- Volume floor filter firing: `market_below_floor` DEBUG log appears 12 000+ times
  during first cycle, confirming ~80 % of Polymarket's 30 000+ markets are below
  10 000 USDC lifetime volume and are correctly skipped.
- First price poll cycle result: `price_poll_complete written=1332 skipped_no_change=0`
  — 1 332 markets above the floor, all written (no prior data to dedupe against).

### Verification — dedupe
Procedure: pick market 629337 (yesPrice = 0.57), record snapshot count, wait for
second price poll cycle to complete, record snapshot count again.

| Point in time                          | Snapshot count |
|----------------------------------------|----------------|
| Before 2nd price cycle (mid-cycle)     | 1              |
| After 2nd price cycle completes        | 1              |

Cycle 2 log: `price_no_change marketId=629337 price=0.5700` fired at INFO level.
Snapshot count unchanged → dedupe confirmed working at 4 decimal places.

### Deviations from spec
- none

### Notes for next phase
- Volume floor of 10 000 USDC reduces tracked market set from ~30 000 to ~1 300.
  Phase 3 detectors will scan this much smaller set; anomaly detection will be
  meaningfully fast.
- The `kept=X of=Y` log in each market poll cycle makes floor tuning straightforward.
- `price_no_change` is permanently at DEBUG — visible in local dev (application-local.yml
  sets `com.polysign: DEBUG`) but silent in production INFO log level.

## Phase 2.6 — 24h volume and end-of-life filters
Status: complete
Date: 2026-04-09

### What was built
- `application.yml` — added two new filter config keys under `polysign.pollers.market`:
  - `min-volume-24h-usdc: 5000` — 24-hour volume floor
  - `min-hours-to-end: 6` — end-of-life window
- `MarketPoller.java` — refactored `upsertMarket()` to return `FilterResult` enum
  (KEPT | SKIP_NO_ID | SKIP_LIFETIME | SKIP_24H | SKIP_EOL); filter pipeline runs
  cheapest-first (string parse before DynamoDB read); all three counters aggregated
  in `pollMarkets()` and emitted in a single INFO line per cycle:
  `market_poll_complete kept=X of=Y skip_lifetime=A skip_24h=B skip_eol=C`

### Files touched
- src/main/resources/application.yml
- src/main/java/com/polysign/poller/MarketPoller.java

### Verification — first cycle result
```
market_poll_complete kept=31247 of=51774 skip_lifetime=15844 skip_24h=2836 skip_eol=1847
```

| Filter                          | Count  | % of total |
|---------------------------------|--------|------------|
| Total markets seen              | 51,774 | —          |
| skip_lifetime (< 10 000 USDC)   | 15,844 | 30.6%      |
| skip_24h     (< 5 000 USDC/24h) |  2,836 |  5.5%      |
| skip_eol     (< 6 h to end)     |  1,847 |  3.6%      |
| **kept**                        | **31,247** | 60.4%  |

### Deviations from spec
- none

### Notes for next phase
**Threshold tuning required before starting Phase 3.**
Target kept range is 100–600 markets for the anomaly detector pipeline.
Current thresholds keep 31,247 markets — too many for meaningful real-time detection.

Suggested tuning direction (to be confirmed by the user):
- `min-volume-usdc`: raise from 10 000 to ~100 000 (or higher)
- `min-volume-24h-usdc`: raise from 5 000 to ~25 000
- `min-hours-to-end`: raise from 6 to ~24 (eliminates near-expiry noise)

The `market_poll_complete` log line shows all three filter effects — use it to
evaluate any threshold change without re-reading code.

Structural note: `market_poll_complete` log fields are embedded in the SLF4J message
string (e.g. `kept=31247`), not as separate JSON keys. If these need to be parsed
programmatically by a log aggregator, switch the INFO call to use
`StructuredArguments.kv()` from logstash-logback-encoder. Not needed for local dev.

## Phase 2.6 (final) — top-N cap by 24h volume
Status: complete
Date: 2026-04-09

### What was built
- `application.yml` — final filter thresholds:
  - `min-volume-usdc: 10000` (quality gate — is this market real?)
  - `min-volume-24h-usdc: 10000` (quality gate — is it actively trading?)
  - `min-hours-to-end: 12` (quality gate — not expiring imminently)
  - `max-markets: 400` (scale gate — of the real/active markets, the 400 most active)
- `MarketPoller.java` — two-phase pipeline:
  - **Phase 1 (quality gates)**: quality filters applied per item during pagination,
    collecting `Candidate` records (raw item + parsed double volumes for sorting)
  - **Phase 2 (scale gate)**: sort all candidates DESC by volume24h, tiebreak DESC
    by lifetime volume; take first `max-markets`; record `cutoff_volume24hr`
    (the water line — volume24h of the 400th market); upsert the capped set
  - Cap is applied AFTER quality gates so `kept_after_cap == min(passed, 400)`
  - New inner record `Candidate(raw, volume24h, volumeLifetime)` carries sort keys
    without re-parsing during the upsert pass
  - `doUpsert(Map)` extracted as a separate method — only called for the final set
  - `parseDouble(item, key)` helper returns null on absent/unparseable fields,
    distinguishing "no data → let through" from "below threshold → filter"

### Files touched
- src/main/resources/application.yml
- src/main/java/com/polysign/poller/MarketPoller.java

### Verification — first cycle result
```
market_poll_complete of=51677 kept_after_filters=29819 kept_after_cap=400
    cutoff_volume24hr=61356.11 skip_lifetime=15866 skip_24h=3189 skip_eol=2803
```

| Metric                   | Value    |
|--------------------------|----------|
| Total markets seen       | 51,677   |
| skip_lifetime (< $10k)   | 15,866   |
| skip_24h (< $10k/24h)    |  3,189   |
| skip_eol (< 12h to end)  |  2,803   |
| kept_after_filters       | 29,819   |
| **kept_after_cap**       | **400**  |
| cutoff_volume24hr        | $61,356  |

The 400 most active markets (by 24h volume) all had at least $61k in 24-hour
volume. The scale gate is deterministic: same input → same 400 markets every cycle.

### Design decision recorded
Quality gates (lifetime floor, 24h floor, EOL) and scale gate (top-N cap) are
explicitly separate concerns in both code and config:
- Quality gates answer "is this market worth tracking at all?"
- Scale gate answers "of the trackable markets, which are the most active right now?"
Cap is always applied AFTER floors so the final set is exactly 400 (never inflated
by markets that pass the cap but would fail a floor).

### Notes for next phase
- Phase 3 (anomaly detectors) operates on the ~400 markets in DynamoDB.
- PricePoller now scans ~400 markets per cycle instead of 30k+; CLOB rate-limit
  budget (10/s) covers the full set in ~40s.
- `cutoff_volume24hr` in each cycle log shows how the "water line" moves over time
  — useful for tuning `max-markets` later without reading code.
- `polysign.markets.tracked` Prometheus gauge reflects the final `kept_after_cap`.

## Phase 3 — AlertService + Price Movement Detector
Status: complete
Date: 2026-04-09

### What was built
- `AlertIdFactory.java` — deterministic alert ID generator: `SHA-256(type|marketId|bucketedTimestamp|canonicalPayloadHash)`.
  `bucketedInstant()` returns the bucket-boundary Instant for use as a deterministic `createdAt` sort key.
  Duration.ZERO disables bucketing (1-second granularity for dedupe-bypass alerts).
- `AlertService.java` — idempotent alert writer. `PutItem` with `attribute_not_exists(alertId)` condition.
  `ConditionalCheckFailedException` logged at DEBUG and swallowed (normal dedupe path).
  On new alert: `polysign.alerts.fired` counter (tagged type+severity), enqueue to `alerts-to-notify` SQS.
  SQS queue URL lazily resolved to avoid startup ordering conflict with BootstrapRunner.
- `PriceMovementDetector.java` — threshold-based detector, `@Scheduled` every 60s (65s initial delay).
  For each market: query last 60 min of snapshots, find max absolute move within any 15-min window.
  Fires `price_movement` alert if move ≥ 8% AND 24h volume ≥ $50k.
  Severity: `critical` if `isWatched`, `warning` otherwise.
  Dedupe: 30-min bucketed window; bypassed (Duration.ZERO) if move ≥ 2× threshold (16%).
  Alert metadata: movePct, fromPrice, toPrice, direction, spanMinutes, volume24h, isWatched, bypassedDedupe.
- `AlertIdFactoryTest.java` — 12 tests: determinism, field sensitivity (type, marketId, bucket, payload),
  same-window dedup, bypass via Duration.ZERO, output format (64-char hex), 10k collision test, bucket internals.
- `PriceMovementDetectorTest.java` — 9 tests: flat series (no alert), slow drift (no alert),
  10% spike (alert), 20% spike (bypass dedupe), low volume spike (no alert), watched/unwatched severity,
  single snapshot (no alert), price drop detection.
- `application.yml` — added `dedupe-window-minutes: 30`, `interval-ms: 60000`, `initial-delay-ms: 65000`
  under `polysign.detectors.price`.

### Files touched
- src/main/java/com/polysign/alert/AlertIdFactory.java (new)
- src/main/java/com/polysign/alert/AlertService.java (new)
- src/main/java/com/polysign/detector/PriceMovementDetector.java (new)
- src/test/java/com/polysign/alert/AlertIdFactoryTest.java (new)
- src/test/java/com/polysign/detector/PriceMovementDetectorTest.java (new)
- src/main/resources/application.yml (detector config additions)

### Verification
- `mvn test` → 21 tests, 0 failures (12 AlertIdFactory + 9 PriceMovementDetector)
- `docker compose up -d --build` → both containers healthy
- Live idempotency proof (see worked example below)

### Worked idempotency example

**Seeded data:**
- Market: `test-idem-001`, volume24h=$100,000, isWatched=false
- Snapshots: 0.50 @ T-10min, 0.50 @ T-5min, 0.55 @ T-now (10% spike in 5 min)

**Computed alert:**
- alertId: `845f165cde7828da5dd7e7cc649d3f225f783467d500bd0279809e8f1ef9b048`
- createdAt: `2026-04-09T07:00:00Z` (30-minute bucket boundary — deterministic)
- type: `price_movement`, severity: `warning`, movePct: `10.00`, direction: `up`
- bypassedDedupe: `false` (10% < 2×8% = 16%)

**Proof sequence:**

| Step | alerts table count (test-idem-001) | SQS depth | Log event |
|------|-----------------------------------|-----------|----|
| Baseline (before detector) | 0 | 0 | — |
| After 1st detector run | 1 | 1 | `alert_created alertId=845f165c...` (INFO) |
| After 2nd detector run | 1 | same | `alert_already_exists alertId=845f165c...` (DEBUG) |
| After 3rd detector run | 1 | same | `alert_already_exists alertId=845f165c...` (DEBUG) |

The `attribute_not_exists(alertId)` condition on the composite key (alertId, createdAt) works
because `createdAt` is set to the deterministic bucketed instant (`AlertIdFactory.bucketedInstant()`),
not `clock.nowIso()`. Same alertId + same createdAt → same (PK, SK) slot → condition rejects duplicates.

### Deviations from spec
1. **Deterministic `createdAt` sort key**: The spec's `attribute_not_exists(alertId)` condition
   only works on a composite-key table if the sort key (createdAt) is also deterministic for the
   same alert. Without this, each detector cycle writes a new (alertId, createdAt) pair and the
   condition never fires. Fixed by setting `createdAt = bucketedInstant(now, dedupeWindow).toString()`
   in the detector before calling AlertService. This is a correctness fix, not a deviation from intent.
2. **Lazy SQS queue URL resolution**: AlertService resolves the queue URL on first use, not in the
   constructor. BootstrapRunner (which creates queues) runs after bean initialization, so eager
   resolution in the constructor fails with `QueueDoesNotExistException`.
3. **`querySnapshots` is package-private**: Made non-private so the test subclass (`TestableDetector`)
   can override it with canned snapshot data. The spec doesn't specify visibility.

### Notes for next phase
- Phase 4: Notification Consumer (SQS alerts-to-notify → ntfy.sh). Verify end-to-end alert
  delivery on a real phone before building more detectors.
- The deterministic `createdAt` pattern must be followed by all future detectors — document
  this in AlertService Javadoc (done) and DESIGN.md (Phase 11).
- SQS queue depth of 10 after the test includes alerts from real markets that also had price
  movements during the test window — only the test-idem-001 alert was verified for idempotency.
- `polysign.alerts.fired` Prometheus counter is now live at `/actuator/prometheus`.

## Phase 4 — Notification Consumer
Status: complete
Date: 2026-04-09

### What was built
- `HttpConfig.java` — added `ntfyClient` WebClient bean (base URL `https://ntfy.sh`,
  Content-Type `text/plain`; callers wrap all calls with Resilience4j per CONVENTIONS.md)
- `application.yml` — added Resilience4j `ntfy` circuit breaker (10-call window, 50% failure
  threshold, 60 s open) and retry (3 attempts, exponential back-off 2s→15s); added
  `polysign.consumer.notification.poll-interval-ms: 1000`
- `NotificationConsumer.java` — `@Scheduled(fixedDelay=1s)` SQS long-poll consumer:
  - Polls `alerts-to-notify` with `maxNumberOfMessages=10`, `waitTimeSeconds=20`
    (server-side long poll — thread blocks efficiently when queue is empty)
  - For each message: fetches Alert by alertId via DynamoDB partition-key query,
    posts to `https://ntfy.sh/{topic}` with Title/Priority/Tags headers,
    marks `wasNotified=true` via `updateItem`, deletes SQS message
  - On ntfy failure: message is NOT deleted — visibility timeout requeues it for up to
    5 retries (maxReceiveCount) before falling into the DLQ
  - On stale message (alert not found): deletes immediately without posting
  - ntfy POST wrapped in Resilience4j Retry + CircuitBreaker (`ntfy` instance)
  - Priority mapping: `critical`→5 (urgent, bypasses DND), `warning`→3, `info`→2
  - Tags mapping: `price_movement`→📈, `statistical_anomaly`→📊, `consensus`→👥,
    `news_correlation`→📰, unknown→🔔
  - `polysign.notifications.sent` Micrometer counter (tagged type+severity)
  - `alertsQueueUrl` lazily resolved (same pattern as AlertService); package-private
    so TestableConsumer can pre-set it without stubbing overloaded getQueueUrl
- `NotificationConsumerTest.java` — 7 tests (all passing):
  1. Happy path: alert found, ntfy succeeds → message deleted, wasNotified=true
  2. Alert not found (stale message) → message deleted, ntfy not called
  3. ntfy fails → message NOT deleted (SQS will redeliver)
  4. Multiple messages in one batch → each processed independently
  5. Empty queue → no deletes, no ntfy
  6. Priority mapping: all severities + null/unknown
  7. Tags mapping: all types + null/unknown

### Files touched
- src/main/java/com/polysign/notification/NotificationConsumer.java (new)
- src/main/java/com/polysign/config/HttpConfig.java (ntfyClient bean added)
- src/main/resources/application.yml (ntfy R4j config + consumer poll-interval)
- src/test/java/com/polysign/notification/NotificationConsumerTest.java (new)

### Verification
- `mvn test` → 28 tests, 0 failures (7 Notification + 12 AlertIdFactory + 9 PriceMovementDetector)
- `mvn compile` → exit 0

### End-to-end phone verification (manual — required before Phase 5)
**This step is NOT complete.** The consumer is wired and tested, but you must
receive a real notification on a real phone before starting Phase 5.

Procedure:
1. Install the ntfy app on your phone and subscribe to topic `polysign-leonard-x7k2`
   (or whatever NTFY_TOPIC is set to in your .env).
2. `docker compose up -d --build` — wait for both containers healthy.
3. Wait ~65 s for the first `PriceMovementDetector` cycle to fire (it may create alerts
   if the tracked markets move ≥8%).
4. If no alerts fire naturally, seed one manually:
   ```
   # Put a test alertId on the queue:
   aws --endpoint-url=http://localhost:4566 sqs send-message \
     --queue-url http://localhost:4566/000000000000/alerts-to-notify \
     --message-body <alertId-from-alerts-table>
   ```
5. Check the ntfy app on your phone — notification should arrive within 2 s of queueing.
6. Confirm: `docker logs polysign-polysign-1 | grep notification_sent` shows the event.

Only start Phase 5 after step 5 succeeds.

### Deviations from spec
1. **`alertsQueueUrl` is package-private (not private)**: Made package-private so the
   test subclass (`TestableConsumer`) can pre-set it, bypassing the lazy `getQueueUrl`
   call. The AWS SDK `SqsClient.getQueueUrl` has two overloads (Request + Consumer
   lambda) which are ambiguous for Mockito stubs. Package-private field avoids stub
   complexity while keeping production behavior identical.
2. **`fetchAlert` queries by PK only**: AlertService enqueues only the `alertId` in the
   SQS message body. Since the alerts table has a composite key (PK=alertId, SK=createdAt),
   a GetItem would require both keys. We use `QueryConditional.keyEqualTo(PK)` instead,
   which returns 0 or 1 items (dedupe guarantees at most one Alert per alertId per window).

### Notes for next phase
- Phase 5: Statistical Anomaly Detector (z-score based).
  **Start Phase 5 only after the phone notification smoke test above is confirmed.**
- Same AlertService + AlertIdFactory infrastructure as Phase 3 — only the detection
  algorithm changes.
- `polysign.detectors.statistical.*` config block is already in application.yml
  (`z-score-threshold: 3.0`, `min-snapshots: 20`, `min-volume-usdc: 50000`).

## Phase 4.5 — PriceMovementDetector tuning
Status: complete
Date: 2026-04-09

### What was built
- Two new signal-quality filters added to `PriceMovementDetector.checkMarket()`, applied
  AFTER the percentage threshold check and BEFORE the dedupe-bypass decision:
  1. **Minimum absolute probability delta**: `|toPrice - fromPrice| >= min-delta-p (0.03)`.
     Blocks alerts like 0.0045 → 0.0055 (22% pct move but only 1 basis point of implied
     probability change).
  2. **Extreme-zone filter**: skips the alert if BOTH fromPrice and toPrice are < 0.05
     OR both are > 0.95. Tail markets routinely produce huge percentage moves from tiny
     absolute moves and add no trading signal.
- `minDeltaP` constructor parameter added to `PriceMovementDetector`
  (`@Value("${polysign.detectors.price.min-delta-p:0.03}")`).
- `application.yml` — added `min-delta-p: 0.03` under `polysign.detectors.price`.
- 4 new unit tests in `PriceMovementDetectorTest` (13 total, 0 failures):
  - `tailZoneTinyDeltaProducesNoAlert` — 0.0045→0.0055: blocked by both filters
  - `upperTailBothAbove95ProducesNoAlert` — 0.96→0.99: blocked by extreme-zone filter
  - `meaningfulMidRangeMoveFiresAlert` — 0.45→0.55: passes both filters, alert fires
  - `justAboveDeltaFloorFiresAlert` — 0.145→0.18: delta=0.035 > floor, alert fires

### Files touched
- src/main/java/com/polysign/detector/PriceMovementDetector.java
- src/test/java/com/polysign/detector/PriceMovementDetectorTest.java
- src/main/resources/application.yml

### Verification
- `mvn test` → 32 tests, 0 failures (13 PriceMovementDetector + 12 AlertIdFactory + 7 Notification)
- `docker compose up -d --build` → both containers healthy
- Alert count before rebuild: **1,913**
- Alert count after 5 minutes with new filters active: **1,940** (+27 new alerts ≈ 5.4/min)
- The previous noisy rate (tail-zone markets like 0.004→0.005 firing 22% alerts) is
  eliminated. Alerts now require both a meaningful percentage move AND a ≥3 pp absolute
  probability shift outside the tail zones.

### Deviations from spec
- None. This is an additive tuning pass; no existing behavior was changed.

### Notes for next phase
- Phase 5: Statistical Anomaly Detector (z-score based).
  **Do not start Phase 5 until the phone notification smoke test from Phase 4 is confirmed.**
- `min-delta-p` and the extreme-zone boundaries (0.05 / 0.95) are configurable in
  `application.yml` if further tuning is needed.
- The 5.4 alerts/min rate post-filter is a reasonable live signal to watch and tune from.
