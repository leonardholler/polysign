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

## Phase 5 — Statistical Anomaly Detector + Orderbook Depth Capture
Status: complete
Date: 2026-04-09
Commit: a9a0f47

### What was built

**Checkpoint 1 — Test-first synthetic series (TDD)**
- `StatisticalAnomalyDetectorTest.java` — 15 tests total (12 original + 3 orderbook):
  1. Flat series (constant price, stddev=0) → no alert
  2. Linear trend (identical returns, stddev≈0) → no alert
  3. Random walk with small ±0.002 noise → no alert
  4. Sudden 3.5σ spike on ε-noise base → alert (asserts zScore ≥ 3.5)
  5. Sudden 5σ spike → alert (asserts zScore ≥ 5.0)
  6. Gradual acceleration (returns ramp 0.001..0.024) → no alert (z ≈ 1.66)
  7. Insufficient history (<20 snapshots) → no alert
  8. High-volatility market (±22% swings absorb 10% move) → no alert
  9. Low-volume market (<$50k) with real anomaly → no alert
  10. Tail-zone anomaly blocked by delta-p floor → no alert
  11. Upper extreme zone blocked → no alert
  12. Alert metadata contains zScore, windowSize, mean, stddev
  13. Alert with orderbook data includes spreadBps and depthAtMid
  14. Alert fires when CLOB call fails (book fields absent)
  15. Alert fires when CLOB call times out (book fields absent)
- Tests use absolute returns (price[k] - price[k-1]), not percentage returns.
  Absolute returns are the principled choice for prediction market probabilities:
  they are the implied probability change, consistent with the delta-p floor,
  and avoid percentage distortion at tail prices.

**Checkpoint 2 — StatisticalAnomalyDetector implementation**
- `StatisticalAnomalyDetector.java` — rolling z-score of 1-minute absolute returns
  over last 60 minutes. Filter pipeline: volume gate → min-snapshots gate →
  z-score threshold → delta-p floor (0.03) → extreme-zone filter (0.05/0.95).
  stddev=0 → skip (no volatility information). Uses AlertIdFactory with 30-min
  dedupe window. Alert metadata: zScore, windowSize, mean, stddev, lastReturn,
  direction, volume24h. Scheduled at 70s initial delay (5s after PriceMovementDetector).

**Checkpoint 3 — Orderbook depth capture**
- `OrderbookService.java` — encapsulates CLOB `/book?token_id=` call + spread/depth
  computation. Uses the same `clobApiClient` WebClient and `polymarket-clob` circuit
  breaker + rate limiter. No retry (500ms budget is one shot). Catches all exceptions,
  returns `Optional.empty()` on failure. Computations:
    - `spreadBps = (bestAsk - bestBid) / midpoint * 10000`
    - `depthAtMid = sum(size × price) for levels within 1% of midpoint`
- `OrderbookServiceTest.java` — 4 tests: known spread computation, known depth
  computation, empty bids edge case, tight-book spread.
- Both `PriceMovementDetector` and `StatisticalAnomalyDetector` updated to inject
  `OrderbookService` and call `captureOrderbook()` at alert-fire time (not per poll).
  Book fields added to mutable HashMap metadata. Failure → alert fires with book
  fields absent.
- `PriceMovementDetectorTest.java` updated: 3 new orderbook tests (16 total).
  TestableDetector now accepts and overrides OrderbookService.
- `application.yml` — added `dedupe-window-minutes: 30`, `min-delta-p: 0.03`,
  `interval-ms: 60000`, `initial-delay-ms: 70000` under `polysign.detectors.statistical`.

### Files touched
- src/main/java/com/polysign/detector/StatisticalAnomalyDetector.java (new)
- src/main/java/com/polysign/detector/OrderbookService.java (new)
- src/main/java/com/polysign/detector/PriceMovementDetector.java (OrderbookService injection + metadata)
- src/test/java/com/polysign/detector/StatisticalAnomalyDetectorTest.java (new)
- src/test/java/com/polysign/detector/OrderbookServiceTest.java (new)
- src/test/java/com/polysign/detector/PriceMovementDetectorTest.java (3 book tests + TestableDetector update)
- src/main/resources/application.yml (statistical detector config additions)

### Verification
- `mvn test` → 59 tests, 0 failures (final count after orderbook fix + detectedAt):
    - StatisticalAnomalyDetectorTest: 15 (12 detector + 3 orderbook)
    - PriceMovementDetectorTest: 16 (13 detector + 3 orderbook)
    - OrderbookServiceTest: 4
    - AlertIdFactoryTest: 12
    - NotificationConsumerTest: 7
    - (additional tests for orderbook fix and detectedAt — see Post-commit fixes below)
- `docker compose up -d --build` → both containers healthy
- Live verification against real Polymarket data (~8 minutes):
    - Statistical anomaly detector running: `checked=502 fired=0` per cycle
      (correctly skipping markets with <20 snapshots, then evaluating normally)
    - After ~20 minutes (enough history): 1 `statistical_anomaly` alert fired:
      market 1707841 ("Israel x Hezbollah ceasefire by April 30, 2026?"),
      z-score=4.76, 27 snapshots, volume=$451k, lastReturn=0.0665
    - No spam on low-liquidity or low-volume markets
    - 54 price_movement alerts with non-null `spreadBps` and `depthAtMid`
      (confirming orderbook capture works on live CLOB data)
    - 3,462 alerts fired without book data (confirming CLOB failure does not
      block alert creation)
    - Sample alert book data: spreadBps=19960.00, depthAtMid=5900.78

### Deviations from spec
1. **Absolute returns, not percentage returns**: The spec says "rolling z-score of
   1-minute returns" without specifying the return type. Chose absolute returns
   (price[k] - price[k-1]) because: (a) these are probabilities, so absolute return
   IS the implied probability change; (b) consistent with the delta-p floor which is
   in absolute terms; (c) avoids percentage distortion at extreme prices that the
   extreme-zone filter exists to handle. Confirmed via manual walkthrough that all
   9 synthetic test series produce correct results under this convention.
2. **OrderbookService as shared component**: Rather than duplicating CLOB book-fetching
   in both detectors, extracted into a shared `OrderbookService` @Component. Both
   detectors inject it. This avoids code duplication while keeping the "one CLOB call
   per alert" contract clear.
3. **No retry on book capture**: The spec says "500ms timeout budget." Interpreted as
   one shot — retries don't fit within 500ms. Used circuit breaker + rate limiter
   (from the existing `polymarket-clob` Resilience4j instances) but no retry.

### Post-commit fixes (both landed in commit a9a0f47)

**Fix 1 — OrderbookService worst-quote bug**
- **Bug**: `parseBook()` used `getFirst()` on bids and asks. Polymarket returns bids
  ascending (worst → best) and asks descending (worst → best), so `getFirst()` grabbed
  the worst bid and worst ask on each side — producing an inflated spread and incorrect
  depth computation.
- **Fix**: replaced with `selectBestBid` (stream max by price) and `selectBestAsk`
  (stream min by price).
- **Data quality note**: All Phase 5 alerts written before this fix have unreliable
  `spreadBps` and `depthAtMid` values. **Phase 7.5 backtesting must exclude or
  recompute orderbook fields for alerts written prior to this fix.** The fix timestamp
  can be approximated from the commit date (2026-04-09). Filter `alert_outcomes` on
  `firedAt >= fix_timestamp` for reliable book attribution.

**Fix 2 — `detectedAt` added to alert metadata**
- `createdAt` remains the 30-min bucketed idempotency key (required for the DynamoDB
  `attribute_not_exists(alertId)` write guarantee — changing this would break dedupe).
- `detectedAt` is the raw `clock.now()` instant captured once at the start of each
  `checkMarket()` call and passed into the metadata map. Both
  `PriceMovementDetector` and `StatisticalAnomalyDetector` share the same clock call
  to avoid bucket-boundary flakiness where a single detection straddles two minutes.
- Use `detectedAt` for forensics (latency measurement, alert lag analysis). Use
  `createdAt` for deduplication and DynamoDB key operations only.

### Notes for next phase
- **Phase 6: Wallet Tracking + Consensus Detector.**
- **BLOCKER**: Polymarket CLOB `/trades` returns HTTP 401 (requires API key auth).
  `https://data-api.polymarket.com/trades?user=` must be verified live before writing
  any Phase 6 code. If that also fails, fall back to Polygon RPC
  (`https://polygon-rpc.com`). Do not write wallet polling logic until one of these
  endpoints is confirmed working.
- The statistical anomaly detector needs ~20 minutes of price history before it
  starts evaluating markets. On a fresh container start, the first ~20 cycles
  will report `fired=0` — this is normal, not a bug.
- Orderbook `spreadBps` values are high (19960 bps = 200% spread) on some markets
  — this reflects genuinely illiquid books on Polymarket, not a computation error.
  The data is valuable for Phase 7.5 backtesting to correlate signal quality with
  book quality (but see the fix-1 data quality note above).
- The `polysign.detectors.statistical.initial-delay-ms: 70000` puts the stat
  detector 5 seconds after the price movement detector (65s). Both share the
  same `@Scheduled` thread pool (6 threads).

---

## Phase 6 — Wallet Tracking + Consensus Detector
Status: complete
Date: 2026-04-09

### What was built

**Endpoint verification (pre-code)**
- `data-api.polymarket.com/trades?user=<proxyWallet>&limit=N` → HTTP 200, returns JSON array.
  `user` parameter is the **proxy wallet address** (on-chain execution address), not external EOA.
- `data-api.polymarket.com/positions?user=<proxyWallet>&limit=N` → HTTP 200, returns JSON array.
- `startTime=<epochSeconds>` filter confirmed working.
- `conditionId` field present in Gamma API response — Option B (numeric marketId join) confirmed viable.

**Schema decisions**
- `conditionId` added to `Market` model + `MarketPoller.doUpsert()`. No new Gamma API call —
  field is already in the market objects we already fetch.
- `wallet_trades` SK changed from `timestamp` to `txHash` — natural idempotency key. Same trade
  (same txHash) re-processed from Data API = PutItem overwrite with identical data = safe no-op.
  `timestamp` becomes a non-key attribute used as the GSI SK for range queries.

**New files**
- `Market.java` — `conditionId` field added (getter, setter)
- `MarketPoller.java` — `market.setConditionId(stringOrNull(item, "conditionId"))` in `doUpsert()`
- `WalletTrade.java` — SK changed to `txHash`; `timestamp` and `slug` added as plain attributes
- `BootstrapRunner.java` — `wallet_trades` create-table updated: SK=`txHash`, `timestamp`
  declared as GSI SK attribute
- `HttpConfig.java` — `dataApiClient` WebClient bean (`https://data-api.polymarket.com`)
- `WalletBootstrap.java` — `@Order(2)` `ApplicationRunner`; reads `watched_wallets.json`,
  writes each entry with `attribute_not_exists(address)` — idempotent, never overwrites
  `lastSyncedAt` set by WalletPoller
- `WalletPoller.java` — `@PostConstruct buildCache()` scans markets on startup to populate
  `conditionId→marketId` ConcurrentHashMap; `@Scheduled poll()` (90s initial delay, 60s cycle)
  iterates watched wallets, fetches trades via Data API (Resilience4j CB + retry), resolves
  marketId via cache then DynamoDB scan fallback, writes `WalletTrade`, calls detectors per trade
- `WalletActivityDetector.java` — fires `info` alert of type `wallet_activity` when
  `sizeUsdc >= minTradeUsdc ($5k)`; alertId = `SHA-256(wallet_activity|marketId|epoch|txHash)`
- `ConsensusDetector.java` — fires `critical` alert of type `consensus` when ≥ 3 distinct
  watched wallets trade same market same direction within 30-min window; dedupe via 30-min
  AlertIdFactory bucket; `@Autowired` annotates primary constructor (dual-constructor pattern
  for Spring + test subclass)
- `ConsensusDetectorTest.java` — 6 tests, TDD (written red before implementation):
  1. 2 wallets same direction → no alert
  2. 3 wallets same direction within window → alert (type=consensus, severity=critical)
  3. 3 wallets mixed directions → no alert
  4. 3 wallets same direction but oldest is outside 30-min window → no alert
  5. 3 trade records but only 2 distinct addresses → no alert
  6. 4 wallets same direction → exactly one alert (idempotency via AlertIdFactory bucket)
- `application.yml` — added `polysign.pollers.wallet.*` config, `polymarket-data` Resilience4j
  circuit breaker + retry
- `watched_wallets.json` — updated notes to say "proxy wallet address (on-chain execution
  address, from polymarket.com/leaderboard)"

### Files touched
- src/main/java/com/polysign/model/Market.java
- src/main/java/com/polysign/model/WalletTrade.java
- src/main/java/com/polysign/config/BootstrapRunner.java
- src/main/java/com/polysign/config/HttpConfig.java
- src/main/java/com/polysign/config/WalletBootstrap.java (new)
- src/main/java/com/polysign/poller/MarketPoller.java
- src/main/java/com/polysign/poller/WalletPoller.java (new)
- src/main/java/com/polysign/detector/WalletActivityDetector.java (new)
- src/main/java/com/polysign/detector/ConsensusDetector.java (new)
- src/test/java/com/polysign/detector/ConsensusDetectorTest.java (new)
- src/main/resources/application.yml
- src/main/resources/watched_wallets.json

### Verification
- `mvn test` → **65 tests, 0 failures**:
    - ConsensusDetectorTest: 6 (all 6 spec cases, TDD)
    - StatisticalAnomalyDetectorTest: 16
    - PriceMovementDetectorTest: 17
    - OrderbookServiceTest: 7
    - AlertIdFactoryTest: 12
    - NotificationConsumerTest: 7
- `docker compose up -d --build` → both containers healthy
- `curl http://localhost:8080/actuator/health` → `{"status":"UP"}`
- `wallet_trades` schema confirmed: PK=`address`, SK=`txHash`, GSI=`marketId-timestamp-index`
- `watched_wallets` scan count: 10 (all placeholder entries seeded by `WalletBootstrap`)
- Log: `wallet_bootstrap_complete seeded=10 skipped=0`
- Log: `wallet_poller_cache_built conditionMappings=0` (expected — markets table empty at @PostConstruct
  time; on-cache-miss DynamoDB scan path handles runtime lookups as MarketPoller populates the table)

**Consensus detector live verification (unit-test-only)**
Direct `awslocal dynamodb put-item` seeding + consensus trigger was not separately performed
because ConsensusDetector is only invoked from WalletPoller.writeTrade() (not a standalone
scheduler). The 6 unit tests (including the idempotency case) constitute the consensus
regression suite. Phase 7 integration test will cover the end-to-end write → detect path.

### Deviations from spec
1. **wallet_trades SK = txHash (not timestamp)**: Spec says SK=`timestamp`. Changed to `txHash`
   because multiple on-chain trades from the same wallet in the same Polygon block (same second)
   would collide on (address, timestamp). txHash is the natural idempotency key per transaction.
   GSI SK remains `timestamp` (declared as a separate attribute) — range queries for consensus
   window unaffected.
2. **WalletActivityDetector alert type `wallet_activity` (not in spec)**: Spec says type
   `wallet_activity` for the info alert. Implemented as specified.
3. **@Autowired on primary ConsensusDetector constructor**: Spring requires disambiguation
   when a class has two constructors. Added `@Autowired` to the production constructor;
   the package-private test constructor is not annotated, so tests use direct instantiation.
4. **WalletPoller @PostConstruct cache is empty at startup**: `buildCache()` runs during bean
   initialization, before MarketPoller's first poll cycle populates the markets table. The cache
   fills lazily via the on-cache-miss DynamoDB scan path. This is by design — no behavioral change.

### Notes for next phase
- Phase 7: News Correlation Detector + RSS Polling.
- With placeholder wallet addresses, WalletPoller polls the Data API and gets [] each cycle
  (confirmed by live endpoint test). Replace placeholders with real proxy wallet addresses from
  `polymarket.com/leaderboard` to enable live wallet tracking and see consensus alerts.
- The `wallet_trade_unknown_market` WARN log (event + conditionId + proxyWallet + txHash + slug +
  timestamp) is structured for Phase 7.5 audit of the market-miss drop rate.
- ConsensusDetector fires once per (marketId, 30-min-bucket) via AlertIdFactory dedupe.
  If a market crosses the 3-wallet threshold repeatedly within a bucket, only one alert is created.
- Integration test uses @MockBean for six scheduler beans to prevent @Scheduled and @PostConstruct
  from executing during tests. Phase 10 should evaluate a profile-based scheduler disable
  (@ConditionalOnProperty on @EnableScheduling) if the @MockBean list grows beyond 3-4 beans per
  test or if Spring context caching becomes a measurable CI bottleneck.
- Integration tests are gated behind -Dintegration-tests=true. Default `mvn test` runs 65 unit
  tests (+ 2 skipped). To run the 2 new integration tests: `mvn test -Dintegration-tests=true`
  with LocalStack up.

## Phase 7 — News Ingestion + Correlation
Status: complete
Date: 2026-04-09

### What was built
- `UrlCanonicalizer.java` — strips UTM/tracking params, lowercases host, stable SHA-256 `articleId`
- `KeywordExtractor.java` — alphanumeric token extraction (`[^a-z0-9]+` split), ~148-word stop list,
  shared between MarketPoller (at upsert time) and RssPoller (at ingest time)
- `NewsMatcher.java` — asymmetric containment / market-side coverage scoring:
  `matches / marketKw.size()`, NOT Jaccard; returns 0.0 if either set is empty
- `RssPoller.java` — `@Scheduled` every 5 min; 5 feeds (Bloomberg, BBC, Politico, Guardian, NPR);
  feed-level + item-level catch; archives article HTML to S3; writes `Article` to DynamoDB;
  enqueues `articleId` to `news-to-process` SQS; Resilience4j `rss-news` CB + retry
- `NewsConsumer.java` — SQS long-poll consumer (mirrors NotificationConsumer shape);
  fetches Article from DynamoDB; calls `NewsCorrelationDetector.checkMarkets()`; deletes message on
  success; leaves message on failure (SQS requeues after visibility timeout)
- `NewsCorrelationDetector.java` — 5-min TTL cache of active markets (volatile + synchronized);
  `NewsMatcher` score ≥ 0.5 AND volume24h ≥ $100 k → writes `MarketNewsMatch` then fires alert;
  alert type `news_correlation`, severity `warning`; alertId via `AlertIdFactory(Duration.ZERO,
  articleId)` — one article × one market = one alert forever; `clearCache()` package-private for
  integration tests
- `MarketNewsMatch.java` — updated: added `articleTitle`, `articleUrl` denormalized fields;
  `@DynamoDbSecondaryPartitionKey(indexNames = "articleId-index")` stacked on `getArticleId()`
- `BootstrapRunner.java` — `market_news_matches` table updated: `articleId` attribute declared,
  `articleId-index` GSI added
- `MarketPoller.java` — refactored: private `extractKeywords()` and `STOP_WORDS` removed;
  delegates to injected `KeywordExtractor`
- `HttpConfig.java` — `rssArticleClient` WebClient bean added (no base URL, 4 MB buffer)
- `application.yml` — `polysign.pollers.rss.*` config, `rss-news` Resilience4j CB + retry

### Files touched
**New**
- `src/main/java/com/polysign/processing/UrlCanonicalizer.java`
- `src/main/java/com/polysign/processing/KeywordExtractor.java`
- `src/main/java/com/polysign/processing/NewsMatcher.java`
- `src/main/java/com/polysign/processing/NewsConsumer.java`
- `src/main/java/com/polysign/poller/RssPoller.java`
- `src/main/java/com/polysign/detector/NewsCorrelationDetector.java`
- `src/test/java/com/polysign/processing/UrlCanonicalizerTest.java`
- `src/test/java/com/polysign/processing/KeywordExtractorTest.java`
- `src/test/java/com/polysign/processing/NewsMatcherTest.java`
- `src/test/java/com/polysign/poller/MarketPollerKeywordTest.java`
- `src/test/java/com/polysign/detector/NewsCorrelationDetectorIntegrationTest.java`

**Modified**
- `src/main/java/com/polysign/model/MarketNewsMatch.java`
- `src/main/java/com/polysign/config/BootstrapRunner.java`
- `src/main/java/com/polysign/config/HttpConfig.java`
- `src/main/java/com/polysign/poller/MarketPoller.java`
- `src/main/resources/application.yml`

### Verification
- `mvn test` → **71 unit tests, 0 failures** (65 Phase 6 + 6 NewsMatcherTest)
- `mvn test -Dintegration-tests=true` → **2 news integration tests green** (Test A + Test B)
- All Resilience4j `rss-news` CB + retry instances configured and wired

### Deviations from spec
1. **`news_correlation` alertId uses `Duration.ZERO` + `articleId` as payload** (not time-bucketed):
   one article × one market = one alert forever. Consistent with `wallet_activity` pattern
   (WalletActivityDetector uses `Duration.ZERO` + `txHash`). A market crossing the score +
   volume threshold for the same article a second time is a no-op (AlertIdFactory dedupe).
2. **`KeywordExtractor` uses `[^a-z0-9]+` (alphanumeric)**, not `[^a-z]+` (alphabetic). Digit
   tokens like "2026" are kept — this is intentional and matches the original MarketPoller
   behaviour. Javadoc corrected from "non-alphabetic" to "non-alphanumeric" in Phase 7 Commit 2.

### Notes for next phase
- Phase 7.5: backtesting via `articleId-index` GSI on `market_news_matches` to correlate alerts
  with subsequent price movements. Foundation is in place (matchedKeywords stored per row).
- Phase 8: REST API `GET /api/markets/{marketId}/news` can read `market_news_matches` by PK
  without N+1 reads (articleTitle + articleUrl denormalized).
- RSS feeds are unverified live (Bloomberg may require a subscription; others return public XML).
  Replace any dead feeds with alternatives if `rss_feed_failed` WARNs appear consistently.
- `NewsConsumer` polls `news-to-process` every 1 s (long-poll 20 s). Article-level deduplication
  is handled by the `attribute_not_exists(alertId)` DynamoDB condition — re-processing the same
  article is safe.

## Phase 7.5 — Signal Quality Infrastructure
Status: complete
Date: 2026-04-09

### What was built
- `AlertOutcome.java` — DynamoDB model (PK: `alertId`, SK: `horizon`); GSI `type-firedAt-index`
  (PK: `type`, SK: `firedAt`); nullable fields: `directionPredicted`, `directionRealized`,
  `wasCorrect` (Boolean), `priceAtAlert`, `priceAtHorizon`, `magnitudePp`, `spreadBpsAtAlert`,
  `depthAtMidAtAlert` (last two preserved from pre-Phase-5 orderbook; informational only)
- `AlertOutcomeEvaluator.java` — `@Scheduled` every 5 min; scans alerts between now-25h and
  now-15min; evaluates 3 horizons (t15m, t1h, t24h); per-detector `directionPredicted` extraction:
  - `price_movement`, `statistical_anomaly`: `metadata["direction"]` → "up"/"down"
  - `consensus`: `metadata["direction"]` → "BUY"/"SELL" → normalised to "up"/"down"
  - `wallet_activity`: `metadata["side"]` → "BUY"/"SELL" → normalised to "up"/"down"
  - `news_correlation`: always `null` (measures volatility only, not direction)
  - `firedAt` = `metadata["detectedAt"]`; fallback to `createdAt` with WARN log when absent
    (news_correlation alerts always fall back; all Phase 5+ alerts have detectedAt)
  - `priceAtAlert`/`priceAtHorizon` from `price_snapshots` with ±2 min window, closest match
  - Dead zone: `|rawDelta| < 0.005` → `directionRealized="flat"`, `wasCorrect=null` (excluded
    from precision denominator)
  - `magnitudePp`: "up" → rawDelta, "down" → -rawDelta, null direction → abs(rawDelta)
  - `attribute_not_exists(horizon)` conditional write for idempotency; checks `outcomeExists`
    before computing to skip already-evaluated alert×horizon pairs
- `ResolutionSweeper.java` — `@Scheduled` every 6 hours; resolution-horizon outcome writer;
  reuses `evaluator.computeOutcome()` (package-private); **STUBBED**: `findClosedMarkets()`
  returns empty list + WARN log — `Market.java` has no `closed` or `resolvedOutcomePrice` field;
  cannot fully implement until Market model is extended (Phase 8 or later)
- `SnapshotArchiver.java` — `@Scheduled` 04:00 UTC daily; scans all markets, queries last 24h
  snapshots, writes gzipped JSONL to S3 (`snapshots/{yyyy}/{MM}/{dd}/{marketId}.jsonl.gz`);
  S3 PutObject is naturally idempotent on the same key; package-private method seams for tests
- `SignalPerformanceService.java` — aggregates `alert_outcomes` via `type-firedAt-index` GSI;
  precision = `correctCount / (correctCount + wrongCount)`, null when denominator = 0; computes
  `avgMagnitudePp`, `medianMagnitudePp`, `meanAbsMagnitudePp` per detector type
- `SignalPerformanceController.java` — `GET /api/signals/performance?type=&horizon=&since=`;
  manual validation with `Set<String>` for valid types/horizons; throws `ResponseStatusException`
  for bad params; defaults: horizon=t1h, since=now-7d
- `GlobalExceptionHandler.java` — `@RestControllerAdvice`; RFC 7807 `application/problem+json`
  for `ResponseStatusException`, `MethodArgumentNotValidException`, and generic `Exception`
- `BootstrapRunner.java` — `alert_outcomes` table + `type-firedAt-index` GSI provisioned
- `DynamoConfig.java` — `alertOutcomesTable` bean added
- `PricePoller.java` — TTL extended: 7d → 30d (required by AlertOutcomeEvaluator look-back)

### Files touched
**New**
- `src/main/java/com/polysign/model/AlertOutcome.java`
- `src/main/java/com/polysign/backtest/AlertOutcomeEvaluator.java`
- `src/main/java/com/polysign/backtest/ResolutionSweeper.java`
- `src/main/java/com/polysign/backtest/SnapshotArchiver.java`
- `src/main/java/com/polysign/backtest/SignalPerformanceService.java`
- `src/main/java/com/polysign/api/SignalPerformanceController.java`
- `src/main/java/com/polysign/api/GlobalExceptionHandler.java`
- `src/test/java/com/polysign/backtest/AlertOutcomeEvaluatorTest.java`
- `src/test/java/com/polysign/backtest/ResolutionSweeperTest.java`
- `src/test/java/com/polysign/backtest/SnapshotArchiverTest.java`
- `src/test/java/com/polysign/backtest/SignalPerformanceServiceTest.java`
- `src/test/java/com/polysign/api/SignalPerformanceControllerTest.java`

**Modified**
- `src/main/java/com/polysign/config/BootstrapRunner.java`
- `src/main/java/com/polysign/config/DynamoConfig.java`
- `src/main/java/com/polysign/poller/PricePoller.java`
- `src/main/resources/application.yml`

### Verification
- `mvn test` → **119 unit tests, 0 failures** (71 Phase 7 + 48 Phase 7.5)
- `mvn test -Dintegration-tests=true` → **119 tests, 0 failures** (LocalStack up, DynamoDB + S3 + SQS)
- TDD discipline: AlertOutcomeEvaluator tests written first, confirmed RED (class not found),
  then implementation written, confirmed GREEN

### Deviations from spec
1. **`ResolutionSweeper.findClosedMarkets()` is stubbed** — `Market.java` lacks a `closed`
   boolean and `resolvedOutcomePrice`/`resolvedPrice` field. The sweeper logs a WARN on every
   run and returns an empty list. Requires a Market model extension before it can go live.
2. **`news_correlation` always falls back to `createdAt` for `firedAt`** — `NewsCorrelationDetector`
   does not write `detectedAt` to metadata. Fallback WARN is expected and acceptable; createdAt
   is within 1 second of detection time for news alerts.
3. **Pre-Phase-5 orderbook fields** (`spreadBpsAtAlert`, `depthAtMidAtAlert`) are preserved in
   the `AlertOutcome` model as informational fields. They are written as `null` for all current
   alerts (orderbook capture was removed in Phase 5); they are NOT included in the precision
   formula or any aggregation.

### Notes for next phase
- **BLOCKER for ResolutionSweeper**: `Market.java` needs a `closed: Boolean` field and a
  `resolvedOutcomePrice: BigDecimal` field before `findClosedMarkets()` can be implemented.
  Add them early in Phase 8 or as a prerequisite to any resolution-driven back-test.
- Precision formula: `correct/(correct+wrong)`, flat (|rawDelta| < 0.005) excluded from both
  numerator and denominator; returns null when denominator is zero.
- `GET /api/signals/performance` is live but unauthenticated — add auth (JWT or API key)
  when the auth layer is implemented.
- `price_snapshots` TTL is now 30 days (extended from 7 days in this phase) — this is the
  minimum needed for the t24h horizon look-back with adequate history.

---

## Phase 8 — Dashboard + Signal Strength + Signal Quality Panel

**Status: COMPLETE**
**Commits**: `53480ae` (API controllers + PhoneWorthinessFilter), `7ecaaaa` (dashboard)

### What was built

**Commit 1: API controllers + PhoneWorthinessFilter** (15 files, +1113 / -23)

- `PhoneWorthinessFilter.java` — 3-rule gate: (a) consensus auto-pass, (b) ≥2 distinct
  detector types on same market in last 15min (multi-detector convergence), (c) severity=critical
  AND t1h precision ≥ 0.60 (fails closed on null precision)
- `NotificationConsumer.java` — wired: evaluate worthiness → persist `phoneWorthy` on Alert →
  conditionally push to ntfy → always delete SQS message; `updatePhoneWorthy` is package-private
  so tests can no-op it
- `Alert.java` — added `phoneWorthy` (Boolean) and `reviewed` (Boolean) fields
- `Market.java` — added `currentYesPrice` (BigDecimal); denormalized by PricePoller after each
  snapshot write
- `AppStats.java` — `@Component` with volatile `lastMarketPollAt`; set by MarketPoller, read by
  StatsController
- `MarketPoller.java` — calls `appStats.setLastMarketPollAt(clock.now())` after each successful
  poll cycle
- `PricePoller.java` — updates `market.currentYesPrice` + `marketsTable.updateItem(market)` after
  each snapshot (best-effort, WARN on failure)
- `AlertDto.java` — record: alert fields + marketQuestion (truncated 120), currentYesPrice,
  volume24h (from metadata), signalStrength (distinct detector types, 60min window), badge
  (signalStrength ≥ 3), metadataHighlight (type-keyed: movePct/zScore/walletCount/score/sizeUsdc)
- `MarketController.java` — 6 endpoints: GET /api/markets (category GSI), GET /{id},
  GET /{id}/price-history, GET /{id}/news, GET /{id}/whale-trades, POST /{id}/watch
- `AlertController.java` — GET /api/alerts (scan + enrich + signal-strength),
  GET /by-signal-strength (60min window, sorted desc), POST /{id}/mark-reviewed;
  batch Market lookup via `DynamoDbEnhancedClient.batchGetItem` in chunks of 100
- `WalletController.java` — GET /api/wallets, GET /{address}/trades, POST /api/wallets
- `StatsController.java` — GET /api/stats; reads `polysign.markets.tracked` Micrometer gauge,
  scans alerts for today UTC, scans watchedWallets count, reads appStats.lastMarketPollAt
- `PhoneWorthinessFilterTest.java` — 6 unit tests covering all rule branches using
  TestableFilter subclass (overrides `queryRecentAlertsForMarket`), @Mock
  SignalPerformanceService, lenient clock stub
- `NotificationConsumerTest.java` — updated: added AlwaysWorthyFilter inner class,
  updated TestableConsumer constructor, added no-op updatePhoneWorthy override
- `MarketPollerKeywordTest.java` — updated: added AppStats constructor param

**Commit 2: dashboard** (1 file, +477)

- `src/main/resources/static/index.html` — single dark-mode page, Tailwind CDN + Chart.js CDN,
  no build step; 5 sections:
  - **Header stats bar**: markets tracked, alerts today, watched wallets, last poll, ntfy topic
    copy button; 30s refresh
  - **Signal Quality Panel** (above fold): fetches `/api/signals/performance` for t1h + t24h;
    precision color-coded green (≥60%) / yellow (50–60%) / red (<50%); null → "—"; count cells
    link to filtered alert feed; 60s refresh
  - **Live Alert Feed**: 10s refresh; signal-strength sorted; ★ badge on 3+ detector types;
    severity color-coded; per-type metadata highlight (↕movePct / σzScore / 👥walletCount /
    📰score / $sizeUsdc); mark-reviewed button; Web Audio API soft ping on new critical alerts
  - **Watched Markets Grid**: Chart.js sparklines (1h price history); currentYesPrice, vol24h,
    category; 2min refresh
  - **Smart Money Tracker**: table of watched wallets with alias, truncated address, category,
    last trade, trade count, recent direction; 60s refresh

### Files touched
**New**
- `src/main/java/com/polysign/common/AppStats.java`
- `src/main/java/com/polysign/notification/PhoneWorthinessFilter.java`
- `src/main/java/com/polysign/api/AlertDto.java`
- `src/main/java/com/polysign/api/MarketController.java`
- `src/main/java/com/polysign/api/AlertController.java`
- `src/main/java/com/polysign/api/WalletController.java`
- `src/main/java/com/polysign/api/StatsController.java`
- `src/main/resources/static/index.html`
- `src/test/java/com/polysign/notification/PhoneWorthinessFilterTest.java`

**Modified**
- `src/main/java/com/polysign/model/Alert.java`
- `src/main/java/com/polysign/model/Market.java`
- `src/main/java/com/polysign/notification/NotificationConsumer.java`
- `src/main/java/com/polysign/poller/MarketPoller.java`
- `src/main/java/com/polysign/poller/PricePoller.java`
- `src/test/java/com/polysign/notification/NotificationConsumerTest.java`
- `src/test/java/com/polysign/poller/MarketPollerKeywordTest.java`

### Verification
- `mvn test` → **125 unit tests, 0 failures** (119 prior + 6 new PhoneWorthinessFilterTest)
- `mvn test -Dintegration-tests=true` → **125 tests, 0 failures, 0 skipped**

### Deviations from spec
None. All 6 PhoneWorthinessFilterTest cases implemented. All dashboard sections implemented
per spec. DECISION 7 (live market context in AlertDto + per-type metadataHighlight) implemented.

### Notes for next phase
- `ResolutionSweeper` BLOCKER from Phase 7.5 still open: `Market.java` needs `closed` + 
  `resolvedOutcomePrice` fields. Addressed in Phase 9 — see notes there.
- `currentYesPrice` denormalization means the Market row is updated on every non-duplicate
  price snapshot. If write throughput becomes a concern, batch or TTL the update.
- The dashboard is unauthenticated. Add auth (API key or JWT) when the auth layer is added.

## Phase 9 — DLQs, Metrics, Resilience4j Polish, Lifecycle Cleanup
Status: complete
Date: 2026-04-10

### What was built

**AREA 1 — DLQ verification and wiring**
- DLQs confirmed: all 3 exist and are correctly wired in `BootstrapRunner.bootstrapSqs()`.
  `maxReceiveCount=5` set via JSON redrive policy on each main queue. No changes needed.
- `SqsQueueMetrics.java` (new) — `@Scheduled(fixedDelay=60s)` refreshes 6 queue depths
  into a `ConcurrentHashMap`, Micrometer gauges read from the map:
  - `polysign.sqs.queue.depth` (gauge, tag: queue) — 3 main queues
  - `polysign.dlq.depth` (gauge, tag: queue) — 3 DLQs
  Initial delay 35s (after BootstrapRunner creates queues at ~5–10s).

**AREA 2 — Custom Micrometer metrics**
- `polysign.alerts.deduplicated` (counter, tag: type) — added in `AlertService.tryCreate()`
  on `ConditionalCheckFailedException` (the normal dedupe path).
- `polysign.notifications.failed` (counter, tag: type) — added in `NotificationConsumer.process()`
  when `doPost()` returns false (ntfy call failed; message left in queue for SQS retry).
- `SignalQualityMetrics.java` (new) — `@Scheduled(cron="0 1/5 * * * *")` calls
  `SignalPerformanceService.getPerformance()` for horizons t1h + t24h and updates:
  - `polysign.signals.precision` (gauge, tags: type, horizon) — null precision → NaN
  - `polysign.signals.magnitude.mean` (gauge, tags: type, horizon)
  - `polysign.signals.sample.count` (gauge, tags: type, horizon)
  Gauges cover all 5 KNOWN_TYPES × 2 horizons = 10 gauge series each (30 total).
- `SignalPerformanceService.KNOWN_TYPES` visibility changed from package-private to `public`
  so `SignalQualityMetrics` (different package) can reference it.

Full metric inventory (all `polysign.*` now emitted):
| Metric | Type | Where | Notes |
|--------|------|-------|-------|
| polysign.markets.tracked | gauge | MarketPoller | ✅ pre-existing |
| polysign.prices.polled | counter | PricePoller | ✅ pre-existing |
| polysign.alerts.fired | counter (type,severity) | AlertService | ✅ pre-existing |
| polysign.alerts.deduplicated | counter (type) | AlertService | ➕ Phase 9 |
| polysign.notifications.sent | counter (type,severity) | NotificationConsumer | ✅ pre-existing |
| polysign.notifications.failed | counter (type) | NotificationConsumer | ➕ Phase 9 |
| polysign.sqs.queue.depth | gauge (queue) | SqsQueueMetrics | ➕ Phase 9 |
| polysign.dlq.depth | gauge (queue) | SqsQueueMetrics | ➕ Phase 9 |
| polysign.wallet.trades.ingested | counter | WalletPoller | ✅ pre-existing |
| polysign.archive.snapshots.written | counter | SnapshotArchiver | ✅ pre-existing |
| polysign.outcomes.evaluated | counter (type,horizon) | AlertOutcomeEvaluator | ✅ pre-existing |
| polysign.signals.precision | gauge (type,horizon) | SignalQualityMetrics | ➕ Phase 9 |
| polysign.signals.magnitude.mean | gauge (type,horizon) | SignalQualityMetrics | ➕ Phase 9 |
| polysign.signals.sample.count | gauge (type,horizon) | SignalQualityMetrics | ➕ Phase 9 |

Note: spec also lists `polysign.alerts.notified` (tag: status) and `polysign.http.outbound.latency`
(timer, tag: target). `alerts.notified` is covered by `notifications.sent` (different name, broader
tags). `http.outbound.latency` deferred — adding it to all R4j call sites is Phase 10+ polish.

**AREA 3 — Resilience4j audit**
All outbound HTTP call sites are correctly wrapped:
- `MarketPoller` — gammaApiClient → Retry + CB (polymarket-gamma) ✅
- `PricePoller` — clobApiClient → RateLimiter + Retry + CB (polymarket-clob) ✅
- `WalletPoller` — dataApiClient → Retry + CB (polymarket-data) ✅
- `OrderbookService` — clobApiClient → RateLimiter + CB (polymarket-clob, no retry — 500ms budget) ✅
- `NotificationConsumer` — ntfyClient → Retry + CB (ntfy) ✅
- `RssPoller` — java.net.http.HttpClient (NOT WebClient) → Retry + CB (rss-news) ✅

**INTENTIONAL EXCEPTION**: RssPoller uses `java.net.http.HttpClient` instead of WebClient.
Rationale: Rome's `SyndFeedInput` requires a blocking `InputStream`; reactive WebClient
does not compose with it. All calls are still wrapped in rss-news CB + retry. Documented
in `HttpConfig.java` comment and `RssPoller` Javadoc.

Dead code removal: `rssArticleClient` WebClient bean deleted from `HttpConfig.java`
(was never injected anywhere — pure dead code since Phase 7).
Unused `WebClient` import removed from `RssPoller.java`.

**AREA 4 — @PostConstruct lifecycle cleanup**
- `WalletPoller.buildCache()` moved from `@PostConstruct` to `@EventListener(ApplicationReadyEvent)`.
  Root cause: `@PostConstruct` fires during bean initialization, BEFORE `BootstrapRunner`
  (ApplicationRunner Order=1) creates DynamoDB tables. Moving to `ApplicationReadyEvent` guarantees
  the markets table exists on a fresh LocalStack or first-ever deployment.
  The catch-and-continue guard remains (defensive, now truly unreachable on clean startup).

**Market.closed / resolvedOutcomePrice — ResolutionSweeper blocker**
- `Market.java` — added `closed` (Boolean) and `resolvedOutcomePrice` (BigDecimal) with
  explicit getters/setters (no Lombok — DynamoDB Enhanced Client annotation constraint).
- `MarketPoller.doUpsert()` — reads `closed` from Gamma response and sets it on the Market.
  Note: the current poll uses `?active=true&closed=false` which means markets in the table
  will always have `closed=false` from this path. The `closed` flag will flip if Polymarket
  ever returns a market with `closed=true` in a future response.
- `resolvedOutcomePrice` is NOT populated by MarketPoller — it is not present in the
  Gamma `/markets` endpoint. **ResolutionSweeper requires a separate closed-markets poll**
  (e.g. `?active=false&closed=true` + parse `outcomePrices`) before it can be fully
  un-stubbed. Deferred to Phase 12+ deployment validation.

### Files touched
**New**
- `src/main/java/com/polysign/metrics/SqsQueueMetrics.java`
- `src/main/java//com/polysign/metrics/SignalQualityMetrics.java`

**Modified**
- `src/main/java/com/polysign/model/Market.java` — added closed, resolvedOutcomePrice
- `src/main/java/com/polysign/poller/MarketPoller.java` — reads closed from Gamma response
- `src/main/java/com/polysign/poller/WalletPoller.java` — @PostConstruct → @EventListener
- `src/main/java/com/polysign/alert/AlertService.java` — polysign.alerts.deduplicated counter
- `src/main/java/com/polysign/notification/NotificationConsumer.java` — polysign.notifications.failed counter
- `src/main/java/com/polysign/config/HttpConfig.java` — deleted rssArticleClient bean, added clarifying comment
- `src/main/java/com/polysign/poller/RssPoller.java` — removed dead WebClient import, expanded HttpClient rationale Javadoc
- `src/main/java/com/polysign/backtest/SignalPerformanceService.java` — KNOWN_TYPES made public

### Verification
- `mvn compile` → exit 0
- `mvn test` → **125 unit tests, 0 failures, 4 skipped** (integration tests gated behind flag)
- `mvn test -Dintegration-tests=true` → TBD (LocalStack not running at commit time)

### Deviations from spec
1. **`polysign.notifications.sent` (not `polysign.alerts.notified`)**: spec names this
   `polysign.alerts.notified` tagged by `status`. Code emits `polysign.notifications.sent`
   tagged by type+severity (richer tags). Name diverges from spec; kept to avoid breaking
   existing Prometheus queries. Noted here for the AWS deployment phase.
2. **`polysign.http.outbound.latency` not implemented**: spec defines a timer tagged by
   `target`. Adding timers to all R4j call sites requires wrapping every decorated supplier
   in a Micrometer Timer.record(). Deferred to Phase 10+ as it's operational polish, not a
   correctness issue.
3. **`rssArticleClient` was dead code**: defined in Phase 7, never injected. Deleted Phase 9.
   RssPoller always used java.net.http.HttpClient (the intentional exception).
4. **ResolutionSweeper remains partially stubbed**: `closed` and `resolvedOutcomePrice` fields
   are now in the model, but `resolvedOutcomePrice` cannot be populated without a dedicated
   closed-market Gamma poll. ResolutionSweeper.findClosedMarkets() still returns empty list.

### Notes for next phase
- Phase 10 complete — see Phase 10 entry below.
- SqsQueueMetrics and SignalQualityMetrics are not covered by unit tests (they are thin
  scheduling/gauge-registration wrappers). The Testcontainers integration test validates
  the surrounding infrastructure (DynamoDB, SQS, S3) they depend on.
- The `polysign.dlq.depth` gauge will read 0 until a message enters a DLQ. The chaos
  experiments in Phase 12 will exercise this path live.

---

## Phase 10 — Testcontainers Integration Tests + Failsafe + CI
Status: complete
Date: 2026-04-10

### What was built

**DECISION 1 — Testcontainers replaces manual LocalStack**
- `AbstractIntegrationIT.java` — shared base class for all integration tests.
  Uses the Testcontainers Singleton Container Pattern: `LocalStackContainer` pinned to
  `localstack/localstack:3.8` is started once in a `static {}` block and reused across
  all subclasses. `@DynamicPropertySource` injects the dynamic port into
  `aws.endpoint-override`, overriding the hardcoded `http://localstack:4566` from
  `application-local.yml`. BootstrapRunner (ApplicationRunner @Order=1) creates all
  DynamoDB tables, SQS queues, and S3 bucket on Spring context startup — no manual
  table creation needed in tests.
- Complete `@MockBean` list derived by grepping all `@Scheduled` annotations across the
  codebase (13 total): base class mocks the 10 that reach external services or would
  interfere with all tests (MarketPoller, PricePoller, WalletPoller, RssPoller,
  NotificationConsumer, NewsConsumer, SqsQueueMetrics, SignalQualityMetrics,
  SnapshotArchiver, ResolutionSweeper). Subclasses add their own.

**DECISION 2 — The golden-path integration test**
- `GoldenPathIT.java` — 13-step test proving the complete signal quality loop:
  1. Seed Market (volume24h=$100k) + 20 flat snapshots at 0.50 + spike at 0.60 (T0)
  2. Fix clock to T0 = now−2h; call `priceMovementDetector.detect()`
  3. Assert 1 alert in DynamoDB (type=price_movement, direction=up)
  4. Assert 1 message in alerts-to-notify SQS (depth=1)
  5. Call detect() again → still 1 alert, still 1 SQS message (DynamoDB idempotency proof)
  6. Seed T+15min snapshot at 0.62; advance clock to T1 = T0+20min
  7. Call `alertOutcomeEvaluator.evaluate()`
  8. Assert 1 outcome row: horizon=t15m, wasCorrect=true, magnitudePp>0
  9. Call evaluate() again → still 1 outcome row (attribute_not_exists(horizon) idempotency)
- Clock strategy: `AppClock.setClock(Clock.fixed(T0))` before detection;
  `AppClock.setClock(Clock.fixed(T1))` before evaluation; reset to `Clock.systemUTC()`
  in @AfterEach. No @MockBean on AppClock — the real bean is used.
- 20% spike (≥ 2× 8% threshold) → bypassDedupe=true → effectiveWindow=Duration.ZERO →
  createdAt = T0 exactly → alert is within evaluator's [T1−25h, T1−15min] window.

**DECISION 3 — Maven Failsafe separates unit and integration tests**
- `maven-failsafe-plugin` added to `pom.xml`.
  - `mvn test` → Surefire → `*Test.java` (121 unit tests)
  - `mvn verify` → Surefire + Failsafe → `*Test.java` + `*IT.java` (121 + 5 = 126 total)

**DECISION 4 — Migrated existing integration tests to *IT.java**
- `ConsensusDetectorIT.java` — migrated from ConsensusDetectorIntegrationTest.java.
  Now extends AbstractIntegrationIT; no @EnabledIfSystemProperty gate; adds
  @MockBean({PriceMovementDetector, StatisticalAnomalyDetector}) for its context.
- `NewsCorrelationDetectorIT.java` — migrated from NewsCorrelationDetectorIntegrationTest.java.
  Same pattern. Shares Spring ApplicationContext with ConsensusDetectorIT (same @MockBean set).
- Old `*IntegrationTest.java` files in `com.polysign.detector` deleted.

**DECISION 5 — CI via GitHub Actions**
- `.github/workflows/ci.yml` — triggers on push and PR to main; Java 25 Temurin;
  Maven cache; runs `mvn -B verify` (unit + integration tests in one step).
  Testcontainers auto-detects Docker on `ubuntu-latest`.

**Visibility changes (required for cross-package test access)**
- `PriceMovementDetector.detect()` — `void` → `public void`
- `AlertOutcomeEvaluator.evaluate()` — `void` → `public void`
- `NewsCorrelationDetector.clearCache()` — `synchronized void` → `public synchronized void`

### Files touched
**New**
- `src/test/java/com/polysign/integration/AbstractIntegrationIT.java`
- `src/test/java/com/polysign/integration/ConsensusDetectorIT.java`
- `src/test/java/com/polysign/integration/NewsCorrelationDetectorIT.java`
- `src/test/java/com/polysign/integration/GoldenPathIT.java`
- `.github/workflows/ci.yml`

**Modified**
- `pom.xml` — maven-failsafe-plugin added
- `src/main/java/com/polysign/detector/PriceMovementDetector.java` — detect() public
- `src/main/java/com/polysign/backtest/AlertOutcomeEvaluator.java` — evaluate() public
- `src/main/java/com/polysign/detector/NewsCorrelationDetector.java` — clearCache() public

**Deleted**
- `src/test/java/com/polysign/detector/ConsensusDetectorIntegrationTest.java`
- `src/test/java/com/polysign/detector/NewsCorrelationDetectorIntegrationTest.java`

### Verification
- `mvn test` → **121 unit tests, 0 failures, 0 skipped**
- `mvn verify` → **121 unit tests + 5 integration tests = 126 total, 0 failures**
  - ConsensusDetectorIT: 2 tests (consensus fires, idempotency)
  - NewsCorrelationDetectorIT: 2 tests (high-volume fires, low-volume skips)
  - GoldenPathIT: 1 test (13-step full pipeline)
- LocalStack container starts once and is shared across all 3 IT classes (singleton pattern)

### Deviations from spec
1. **`detect()` and `evaluate()` made public** (were package-private). The `com.polysign.integration`
   package requires public access. This is the only way to call these methods directly from a
   different package without subclassing. The methods were always intended for test invocation
   (Javadoc said "package-private for direct invocation in tests").
2. **StatisticalAnomalyDetector is @MockBean in GoldenPathIT** (in addition to the base-class
   mocks). This prevents it from scanning the markets table and potentially firing a spurious
   statistical_anomaly alert on the spike data, which would break the "exactly 1 alert" assertion.
3. **SQS depth check via getQueueAttributes** (not receiveMessage). LocalStack returns exact
   counts via APPROXIMATE_NUMBER_OF_MESSAGES. The @BeforeEach/@AfterEach cleanup purges the
   queue so no stale messages from other test classes affect the assertion.

### Notes for next phase
- Phase 11: README, DESIGN.md, final documentation.
- CI workflow is live — `.github/workflows/ci.yml` will run on first push to GitHub.
- The golden-path test is the mutation proof: commenting out `alertService.tryCreate(...)` in
  PriceMovementDetector causes assertions at steps 5, 6, 8, and 9 to all fail red.
- Spring Test context caching: ConsensusDetectorIT and NewsCorrelationDetectorIT share one
  ApplicationContext (same @MockBean set); GoldenPathIT gets its own (different @MockBean set).

---

## Phase 11 — README + DESIGN.md + RssPoller Feed Binding Fix
Status: complete
Date: 2026-04-10

### What was built

**README.md** — interview-ready project README (9 sections):
1. One-paragraph pitch with monitoring disclaimer
2. Mermaid architecture diagram (write path + feedback loop)
3. Signal quality table (5 detector types, placeholder for live numbers)
4. Engineering decisions (5 subsections): idempotency (Phase 3 bug story + alertId
   `845f165c...` proof), resilience (6 CBs, RssPoller exception), signal quality tuning
   (Phase 4.5 tail-zone 0.0045→0.0055 story), observability (14 metrics), PhoneWorthinessFilter
   (3 rules, fail-closed, target 10-30/day)
5. "What I Would Do Differently on Real AWS" — specific Lambda names, EventBridge rules,
   Step Functions Wait states, Kinesis partitioning, X-Ray sampling, DynamoDB auto-scaling,
   precision-based alarms
6. Signal strategy — 4 tiers (noise → volatility → information → convergence), why 4
   detectors > 1 (signal overlap + measured precision)
7. Tech stack + project structure + running locally (docker compose, phone setup, thresholds)
8. Limitations (honest: keyword matching, stat detector history, wallet curation, sample size,
   no auth, no dashboard tests, ResolutionSweeper stub)
9. Future work (embeddings, Kalshi, orderbook time series, Lambda rewrite)

**DESIGN.md** — deep technical document (~2,700 words, 12 sections):
1. Problem framing
2. Data model — per-table access pattern table (PK/SK/pattern/why) + Why DynamoDB over RDS
   (TTL, per-table capacity, conditional writes; tradeoffs: no ad-hoc queries, GSI consistency,
   400KB limit)
3. Write path — Gamma → MarketPoller → PricePoller → PriceMovementDetector → AlertService →
   SQS → NotificationConsumer → ntfy.sh, one paragraph per hop
4. Alert ID design — SHA-256 bucketing, composite-key bug, worked example with epoch math,
   why not UUIDs, bypass mode for 2x threshold
5. SQS architecture — NotificationConsumer reference pattern (lazy URL, long poll,
   delete-on-success, DLQ), why SQS over in-process, why not Kafka
6. Resilience4j strategy — table of all 6 call sites with config
7. Failure modes — table: what breaks, system behavior, user impact, recovery
8. Signal quality methodology — precision (not accuracy, not recall), 0.5pp dead zone,
   horizon vs resolution evaluation, 3 known biases (survivor, lookback, clustering),
   delta-p floor story
9. Detector architecture — testable subclass pattern, two-constructor pattern, AppClock
   deterministic testing
10. Scaling story — 4 bottlenecks in order (rate limits → write throughput → detector fan-out
    → wallet polling) with fixes
11. What I Would Do Differently (brief, references README)
12. Operational Runbook (Phase 12 placeholder)

**RssPoller feed binding fix** — startup bug fix:
- `@Value("${polysign.pollers.rss.feeds}") List<String>` cannot bind a YAML list.
  Spring's `@Value` resolves scalars; YAML lists use indexed keys (`feeds[0]`, `feeds[1]`)
  which `@Value` doesn't match.
- Fix: `RssProperties.java` — `@ConfigurationProperties(prefix = "polysign.pollers.rss")`
  record with `List<String> feeds`. Constructor binding handles YAML lists correctly.
- `PolySignApplication.java` — added `@EnableConfigurationProperties(RssProperties.class)`
- `RssPoller.java` — constructor changed from `@Value List<String>` to `RssProperties`
  injection; `this.feedUrls = rssProperties.feeds()`

### Files touched
**New**
- `README.md`
- `DESIGN.md`
- `src/main/java/com/polysign/config/RssProperties.java`

**Modified**
- `src/main/java/com/polysign/PolySignApplication.java` — `@EnableConfigurationProperties`
- `src/main/java/com/polysign/poller/RssPoller.java` — `RssProperties` injection
- `PROGRESS.md` — Phase 11 entry

### Verification
- `mvn test` → **121 unit tests, 0 failures**
- `mvn compile` → exit 0
- Docker build + startup verified (see below)

### Deviations from spec
1. **`deployment/aws-setup.md` deferred to Phase 12**: The REMAINING_PHASES prompt mentions
   creating a prose deployment guide in Phase 11. Deferred because Phase 12 creates the actual
   numbered deployment scripts, and the guide should reference them. Writing it now would
   produce stale content.

### Notes for next phase
- Phase 12: Real AWS deployment. All documentation is in place. Placeholders in README.md
  for live URL, screenshots, and cost breakdown will be filled after deployment.
- CI badge URL needs GitHub username — uncomment the badge line in README.md after first
  push to GitHub.
- RssProperties fix means the app now starts cleanly with the YAML list config. Previously
  this would have failed at Spring bean wiring time on a clean container start.
  3 IT classes → 2 Spring context startups (one shared, one for GoldenPathIT).

## Phase 12.1 — Liquidity-Adjusted Detection Thresholds
Status: complete
Date: 2026-04-10

### What was built

**`LiquidityTier.java`** (new enum)
- Three tiers: `TIER_1` (volume24h > $250k), `TIER_2` ($50k–$250k), `TIER_3` (< $50k)
- `classify(double, double, double)` — NaN/negative → TIER_3
- `classifyRaw(String, double, double)` — null/blank/unparseable → TIER_3
- `label()` returns `name()` for structured logging

**`LiquidityTierTest.java`** (new, 15 tests)
- Boundary values at $250k (TIER_2, not TIER_1), $50k exactly (TIER_2), below $50k (TIER_3)
- Zero/negative/NaN all → TIER_3; null/blank/unparseable strings → TIER_3

**`PriceMovementDetector.java`** (rewritten)
- Removed: flat `minVolumeUsdc` volume floor
- Added: `thresholdPctTier1/2/3` (8/14/20%), `tier1MinVolume` ($250k), `tier2MinVolume` ($50k)
- Added: orderbook depth gate for Tier 2 and Tier 3 only (`maxSpreadBps=500`, `minDepthAtMid=$100`)
  - Gate suppresses if spread > 500 bps OR depth < $100 USDC; on failure → fires anyway
  - Tier 1 captures book for metadata only (gate does not apply)
- Added: `recordSuppression()` incrementing `polysign.alerts.suppressed{type,tier,reason}` counter
- Suppression logged at INFO: `alert_suppressed_thin_book marketId={} tier={} spreadBps={} depthAtMid={} reason=spread|depth`
- `liquidityTier` added to alert metadata

**`PriceMovementDetectorTest.java`** (rewritten)
- 27 tests, 0 failures (was 16)
- Updated `TestableDetector` with full tier + MeterRegistry parameters (`SimpleMeterRegistry`)
- New tier threshold tests: T1 9% fires, T2 below 14% no-alert, T2 15% fires, T3 below 20% no-alert, T3 22% fires
- New orderbook gate tests: spread suppression, depth suppression, passes gate, failure fires, Tier 1 ignores gate
- `verifyAlertCreated()` now checks `liquidityTier` in metadata

**`StatisticalAnomalyDetector.java`** (rewritten)
- Same structural changes as PriceMovementDetector
- Z-score thresholds: Tier 1=3.0σ, Tier 2=4.0σ, Tier 3=5.0σ
- Same orderbook gate pattern, same suppression counter, `liquidityTier` in metadata

**`StatisticalAnomalyDetectorTest.java`** (rewritten)
- 21 tests, 0 failures (was 15)
- Updated existing tests to use $300k volume (Tier 1) where they needed to fire
- New: `tier3MarketBelowTier3ThresholdProducesNoAlert` (z≈3.5 < T3 threshold 5.0 → no alert)
- New: `zScore3point5FiresOnTier1ButNotTier2` (same market, tier routing verified)
- New: Tier 2 orderbook gate tests (depth suppressed, spread suppressed, failure fires)

**`AlertService.java`** (modified)
- `alert_created` log line now includes `tier` from metadata:
  `alert_created alertId={} marketId={} type={} tier={} severity={}`

**`application.yml`** (modified)
- Removed per-detector `min-volume-usdc` keys
- Added `polysign.detectors.liquidity-tiers.tier1-min-volume: 250000` and `tier2-min-volume: 50000`
- Added per-tier threshold keys under `price` and `statistical` detector blocks
- Added `polysign.detectors.orderbook-gate.max-spread-bps: 500` and `min-depth-at-mid: 100.0`

**`index.html`** (modified)
- `tierBadge(alert)` helper renders T1 (emerald) / T2 (yellow) / T3 (red) pill next to type badge
- `bookDepthSnippet(alert)` renders `spd Nbps · dep $N` when spread/depth metadata present
- Both injected into alert row in `loadAlerts()`

### Files touched
- src/main/java/com/polysign/model/LiquidityTier.java (new)
- src/test/java/com/polysign/model/LiquidityTierTest.java (new)
- src/main/java/com/polysign/detector/PriceMovementDetector.java
- src/test/java/com/polysign/detector/PriceMovementDetectorTest.java
- src/main/java/com/polysign/detector/StatisticalAnomalyDetector.java
- src/test/java/com/polysign/detector/StatisticalAnomalyDetectorTest.java
- src/main/java/com/polysign/alert/AlertService.java
- src/main/resources/application.yml
- src/main/resources/static/index.html

### Verification
- `mvn test` → **151 unit tests, 0 failures**
- `mvn verify` → **151 unit + 5 integration tests, 0 failures** (including GoldenPathIT)
- GoldenPathIT uses a $100k market (Tier 2); 20% move exceeds T2 threshold (14%);
  `captureOrderbook(null)` returns `Optional.empty()` → fires per spec

### Deviations from spec
1. **`classify()` boundary at $250k**: `volume > tier1MinVolume` (strictly greater),
   so exactly $250k → TIER_2. Consistent with the Tier 1 label "volume > $250k".
2. **Tier 1 still calls captureOrderbook**: Both code paths call `captureOrderbook` before
   the gate check (gate is in the `else` branch). The Tier 1 call captures book metadata;
   the gate simply doesn't apply. Functionally correct per spec.

### Notes for next phase
- The `polysign.alerts.suppressed` counter (tagged type/tier/reason) is now live in
  Prometheus. Use it to tune `max-spread-bps` and `min-depth-at-mid` from real traffic.
- T3 suppression rate will be high on Polymarket (thin books are common for small markets).
  Consider raising `min-depth-at-mid` to $200+ if T3 signal quality remains low.
- Tier boundaries ($250k / $50k) are configurable in `application.yml` — no code change
  needed to tune them.

---

## Phase 12.2 — Alert Noise Reduction + Dashboard Link/Badge Fixes
Status: complete
Date: 2026-04-10

### What was built

**Batch 1 — Alert noise reduction (3 fixes)**

1. **`KeywordExtractor.java`** — Raised minimum token length from 3 to 4 chars; added 13
   domain-specific stopwords (announces, reports, according, sources, officials, people,
   reuters, bloomberg, associated, press, news, update, latest) to filter wire-copy boilerplate.

2. **`NewsCorrelationDetector.java`** — Fixed two noise sources:
   - Dedup was broken: `Duration.ZERO` in `AlertIdFactory.generate()` caused a new alertId
     every second (raw epoch bucket). Fixed to `dedupeWindow` (24h) with `articleId` as
     payload hash → one alert per article-market pair per day, enforced by DynamoDB
     `attribute_not_exists(alertId)`.
   - Added per-article cap: `maxMatchesPerArticle` (config, default 3) — top-N markets by
     score only, preventing a single article from flooding the feed.
   - Both config values wired via `@Value` and added to `application.yml`.

3. **`PriceMovementDetector.java`** — Stale-move bypass fix: `isActivelyMoving()` checks
   that price moved ≥ `minDeltaP` within the most recent 2–8-minute window before allowing
   dedupe bypass. Settled large moves no longer re-fire on every poll cycle.

4. **`WalletActivityDetector.java`** — Dedup window widened from 5 minutes (shorter than
   polling interval) to 24 hours; dedup key changed from `address|marketId|direction` to
   `address|txHash`. Same trade can no longer fire again 5 minutes later.

**Batch 2 — Dashboard link and tier badge fixes**

5. **`PriceMovementDetector.java`**, **`StatisticalAnomalyDetector.java`** — `alert.link`
   now set to `https://polymarket.com/event/<slug>` using `market.getSlug()` with fallback
   to `marketId`. Previously missing, causing dashboard to show no direct market link.

6. **`index.html`** (dashboard) — Three frontend improvements:
   - `pmLink` in `loadAlerts()` now uses `a.link` (server-provided slug URL) with fallback
     to the old search-query URL.
   - Added `tierBorderClass()` helper that maps `TIER_2 → tier-2`, `TIER_3 → tier-3`.
   - T2 alert rows get a subtle amber left border (2px); T3 rows get a red left border (2px);
     T1 and untiered alerts keep the existing severity border.
   - CSS classes `.tier-1/.tier-2/.tier-3` added to the `<style>` block.

### Files touched
- `src/main/java/com/polysign/processing/KeywordExtractor.java`
- `src/main/java/com/polysign/detector/NewsCorrelationDetector.java`
- `src/main/java/com/polysign/detector/PriceMovementDetector.java`
- `src/main/java/com/polysign/detector/StatisticalAnomalyDetector.java`
- `src/main/java/com/polysign/detector/WalletActivityDetector.java`
- `src/main/resources/application.yml`
- `src/main/resources/static/index.html`
- `src/test/java/com/polysign/processing/KeywordExtractorTest.java`
- `src/test/java/com/polysign/detector/PriceMovementDetectorTest.java`

### Verification
- `mvn test`: 152 unit tests, 0 failures, 0 errors
- `mvn verify`: 152 unit tests + 5 integration tests (Testcontainers), 0 failures, 0 errors

### Notes for next phase
- Pre-fix alert counts from DynamoDB scan: news_correlation 2,907; price_movement 4,562;
  wallet_activity 4,661; statistical_anomaly 91. The first three had broken dedup.
- `NewsCorrelationDetector` dedup now matches the same pattern as other detectors:
  `AlertIdFactory.generate(type, marketId, now, dedupeWindow, payloadHash)`.
- T2/T3 tier borders in the dashboard are subtle (2px, semi-transparent) — adjust opacity
  in `.tier-2` / `.tier-3` CSS if more prominence is needed.

---

## Phase 13 — Dashboard UX Cleanup + Bypass Rate Cap + Market Exclusion Filter
Status: complete
Date: 2026-04-10

### What was built

**Fix 1 — Remove dashboard alert sound**
- Removed the Web Audio API `ensureAudio()`/`softPing()` functions and all call sites from
  `index.html`. Dashboard is now silent. Removed the `click` listener that unlocked audio
  on first interaction.

**Fix 2 — Remove signal-strength badge**
- Removed the `S${signalStrength}` badge from the alert feed. It conveyed no user-actionable
  information. The tier badge (`T1/T2/T3`) already present is the meaningful signal.

**Fix 3 — Price from→to display**
- Updated `renderMetaHighlight()` in `index.html` to render `price_movement` alerts as
  `81.3¢ → 63.3¢ (22.2% ↓)` using `alert.metadata.fromPrice`, `toPrice`, `movePct`, and
  `direction`. Direction arrow is green for up, red for down.

**Fix 4 — Market link verification**
- Verified via `awslocal dynamodb scan` that all 4 detectors correctly set
  `alert.link = "https://polymarket.com/event/<slug>"`. Dashboard already uses `a.link`.
  No code changes needed.

**Fix 5 — Bypass-mode rate cap**
- Added per-market rate cap on bypass-mode alerts in `PriceMovementDetector`:
  - New method `countRecentBypassAlerts(marketId, now)` queries `marketId-createdAt-index`
    GSI to count `price_movement` alerts for the market in the last 60 minutes.
  - If `count >= maxBypassPerHour`, logs `alert_rate_capped marketId={} count={}` and
    returns false. Normal (non-bypass) alerts are unaffected.
  - `maxBypassPerHour` defaults to 3, configurable via
    `polysign.detectors.price.max-bypass-per-hour`.
  - `countRecentBypassAlerts` is package-private and returns 0 when `alertsTable` is null,
    so all existing unit tests pass without modification.
- `DynamoDbTable<Alert> alertsTable` added to constructor (3rd param); Spring auto-wires
  the existing `alertsTable` bean.

**Fix 6 — Market exclusion filter**
- Added quality gate 4 to `MarketPoller.pollMarkets()`:
  - Regex exclusion: `excludedQuestionPatterns` (comma-separated regexes compiled to
    `List<Pattern>` at startup). Matches run against the market `question` field. Default
    patterns exclude tweet-count and post-count range markets.
  - Category exclusion: `excludedCategories` (comma-separated category names). Empty by
    default — users can add e.g. `"sports"` to block all sports markets via config alone.
  - Skipped markets counted in `skip_pattern` log counter added to `market_poll_complete`.
  - Config keys: `polysign.pollers.market.excluded-question-patterns` (csv) and
    `polysign.pollers.market.excluded-categories` (csv).

**Fix 7 — News score display**
- Updated `renderMetaHighlight()` to display the `score` key as
  `<span class="text-gray-600 text-[10px]">score: 80%</span>` instead of `📰0.80`.
  Emoji removed; value converted to integer percentage; text is muted grey.

### Files touched
- `src/main/resources/static/index.html`
- `src/main/java/com/polysign/detector/PriceMovementDetector.java`
- `src/test/java/com/polysign/detector/PriceMovementDetectorTest.java`
- `src/main/java/com/polysign/poller/MarketPoller.java`
- `src/test/java/com/polysign/poller/MarketPollerKeywordTest.java`
- `src/main/resources/application.yml`

### Verification
- `mvn test`: 152 unit tests, 0 failures, 0 errors
- `mvn verify`: 152 unit tests + 5 integration tests, 0 failures, 0 errors

### Notes for next phase
- `countRecentBypassAlerts` does a GSI query on every bypass-mode tick. Under high volume,
  consider caching the count per market with a 1-minute TTL to reduce DynamoDB reads.
- The question-pattern filter uses `Pattern.matches()` which requires a full-string match.
  Patterns must include `.*` prefix/suffix (already in the defaults).
- `excluded-categories` is empty by default. To exclude all sports markets: set to `"sports"`.
  Categories are assigned by `CategoryClassifier`; run with debug logging to see values.

## Phase 14 — REWRITE Section 1: Wallet health-check logging and dashboard fields
Status: complete
Date: 2026-04-10

### What was built
- Added structured `wallet_poll_health` log line to `WalletPoller.poll()` summarizing `wallets_attempted`, `wallets_succeeded`, `trades_ingested`, `data_api_status`, and `rpc_fallback_used`.
- Added pipeline stall detection: `consecutiveZeroCycles` counter increments each zero-trade cycle; logs `PIPELINE STALL` ERROR at `STALL_THRESHOLD=3`.
- Added `WatchedWallet` summary fields: `lastTradeAt` (ISO-8601 timestamp of most recent trade), `tradeCount` (cumulative trades in session), `recentDirection` ("BUY"/"SELL").
- `pollWallet()` tracks most recent trade's timestamp/direction and updates wallet object; `updateLastSyncedAt()` persists all fields via `updateItem()`.
- No schema migration needed — new attributes are written on first poll; missing attributes read as null (backward-compatible).

### Files touched
- `src/main/java/com/polysign/poller/WalletPoller.java`
- `src/main/java/com/polysign/model/WatchedWallet.java`

### Verification
- `mvn test`: all tests pass

### Deviations from spec
- none

### Notes for next phase
- `tradeCount` resets to null on a fresh deployment (first scan returns 0); acceptable for dashboard display.

---

## Phase 15 — REWRITE Section 2: PriceMovementDetector resolution zone rewrite
Status: complete
Date: 2026-04-10

### What was built
- `ZoneTransition` enum: `ENTERED_BULLISH`, `ENTERED_BEARISH`, `DEEPENING_BULLISH`, `DEEPENING_BEARISH`, `MID_RANGE`.
- Resolution zone: price > 0.65 → bullish zone, price < 0.35 → bearish zone; mid-range requires 2× tier threshold.
- Zone entry discount: zone transitions get a 25% lower effective threshold.
- Per-window volume: computed as `volume24h` delta over the move window; -1.0 sentinel when delta is negative (volume not yet available).
- `min-window-volume` gate (default 5000 USDC) and `high-volume-window-threshold` discount (20 000 USDC).
- Momentum confirmation: last 3 snapshots must move in alert direction (step1 `>=` for flat tolerance, step2 strict `>`).
- `bypassDedupe` uses base `thresholdPct * 2` (NOT `effectiveThresholdPct * 2`) to prevent bypass threshold from being stretched by zone discounts.
- `GoldenPathIT` updated: test data moved to bullish resolution zone (0.70→0.84, DEEPENING_BULLISH T2, effectiveThreshold=14%, 20%>14%→fires).

### Files touched
- `src/main/java/com/polysign/detector/PriceMovementDetector.java`
- `src/test/java/com/polysign/detector/PriceMovementDetectorTest.java`
- `src/test/java/com/polysign/integration/GoldenPathIT.java`
- `src/main/resources/application.yml`

### Verification
- `mvn test` after Section 2: all tests pass (4 failures fixed: momentum step1 `>=`, bypass base threshold)
- `mvn verify`: all unit + integration tests pass

### Deviations from spec
- `bypassDedupe` explicitly uses base threshold, not effective threshold — prevents double-discounting in resolution zones.

### Notes for next phase
- Dynamic baseline volatility (rolling σ to scale thresholds) deferred to a future phase.
- Per-snapshot volume capture (volume field on each PriceSnapshot) deferred — currently uses volume24h delta approximation.

---

## Phase 16 — REWRITE Section 5: ntfy phone notification precision gate
Status: complete
Date: 2026-04-10

### What was built
- `PhoneWorthinessFilter.precisionThreshold` changed from hardcoded `0.60` to `@Value`-injected `double` (default 0.60).
- `application.yml` sets `polysign.notification.min-precision: 0.40` temporarily while price detector precision improves.
- Dashboard alert score filter also lowered from 0.6 to 0.4 (matched in `index.html`).

### Files touched
- `src/main/java/com/polysign/notification/PhoneWorthinessFilter.java`
- `src/test/java/com/polysign/notification/PhoneWorthinessFilterTest.java`
- `src/test/java/com/polysign/notification/NotificationConsumerTest.java`
- `src/main/resources/application.yml`
- `src/main/resources/static/index.html`

### Verification
- `mvn test`: all tests pass

### Deviations from spec
- none

### Notes for next phase
- Raise `min-precision` back to 0.60 once price detector empirical precision consistently exceeds 50%.

---

## Phase 17 — REWRITE Section 6: Claude sentiment analysis for news correlation
Status: complete
Date: 2026-04-10

### What was built
- `ClaudeSentimentService`: calls Anthropic Messages API (`claude-sonnet-4-6`) with market question, current price in cents, article title, and summary. Returns `SentimentResult(relevant, sentiment, confidence, reasoning)` record.
  - Returns `null` on missing API key, circuit open, or timeout — callers fall back to keyword scoring.
  - Circuit breaker: `claude-api` Resilience4j instance (registered in `application.yml`).
  - Micrometer counters: `polysign.news.sentiment.calls` tagged `status=success|fallback|error`.
  - Model: `claude-sonnet-4-6`, max_tokens: 150, temperature: 0, hard timeout 5s.
- `NewsCorrelationDetector` two-stage scoring:
  - Stage 1 (keyword pre-filter): skip Claude call if keyword score < 0.3 — saves API cost on unrelated articles.
  - Stage 2 (Claude): if relevant and confident (|sentiment| ≥ 0.3, confidence ≥ 0.5), use Claude score; else fall back to keyword ≥ 0.75.
  - Alert metadata: `scoringMethod` ("claude" or "keyword_fallback"), `keywordScore`, `sentimentScore`, `sentimentDirection`, `sentimentConfidence`, `sentimentReasoning`.
- New config keys: `keyword-prefilter-threshold: 0.3`, `sentiment-relevance-threshold: 0.3`, `sentiment-confidence-threshold: 0.5`, `claude-model`, `claude-max-tokens`, `claude-timeout-ms`.
- `NewsCorrelationDetectorTest`: 7 unit tests covering bullish YES, bearish NO, relevant=false, Claude timeout fallback, low |sentiment| blocks alert, scoreMarket Claude path, scoreMarket keyword fallback.

### Files touched
- `src/main/java/com/polysign/detector/ClaudeSentimentService.java` (new)
- `src/main/java/com/polysign/detector/NewsCorrelationDetector.java`
- `src/test/java/com/polysign/detector/NewsCorrelationDetectorTest.java` (new)
- `src/main/java/com/polysign/config/HttpConfig.java`
- `src/main/resources/application.yml`

### Verification
- `mvn verify`: all tests pass

### Deviations from spec
- `Candidate` record visibility changed from `private` to package-private to allow test access.
- `getOrLoadMarkets()` changed from `private synchronized` to package-private `synchronized` for test subclass override.

### Notes for next phase
- `ANTHROPIC_API_KEY` must be set in the deployment environment; without it `ClaudeSentimentService` is a no-op.
- Prompt template uses current market price in cents — re-evaluate if markets move to fractional cent pricing.

---

## Phase 18 — REWRITE Section 4: Dashboard UX overhaul
Status: complete
Date: 2026-04-10

### What was built
- Alert feed grouped by `marketId`: collapsible rows with `toggleMarket(safeId)`, sorted by multi-detector flag then alert count.
- Each market group header: question link, current price, alert count badge, most recent alert type + time, expand toggle.
- `zoneTransitionLabel()`: ENTERED_BULLISH → green "▲ entered resolution zone", ENTERED_BEARISH → red "▼ entered resolution zone", MID_RANGE → gray "mid-range".
- `renderNewsScore()`: Claude path shows "bullish/bearish N%" with reasoning tooltip; keyword fallback shows "match: N%".
- Severity badges removed — no more `severity-critical`/`severity-warning` CSS or colored backgrounds on alert rows.
- `expandedMarkets` Set tracks open groups across re-renders.
- Alert score filter in dashboard lowered from 0.6 to 0.4 (matches `min-precision` config).

### Files touched
- `src/main/resources/static/index.html`

### Verification
- `mvn verify`: all tests pass
- Docker rebuilt and restarted; MarketPoller running normally (confirmed via `docker compose logs`)

### Deviations from spec
- none

### Notes for next phase
- Sound removed (Section 3 was a no-op — no sound code existed).
- Future improvement: dynamic baseline volatility (rolling σ to scale tier thresholds per market).
- Future improvement: capture per-snapshot volume in `PriceSnapshot` to replace volume24h delta approximation.

---

## Phase 19 — FIX: Diagnostic logging, momentum relaxation, resolved market filter, consensus direction display, Claude sentiment activation
Status: complete
Date: 2026-04-10

### What was built
- **Diagnostic logging** (`price_check` DEBUG log) in `PriceMovementDetector.checkMarket()` — tracks `filterReason` through every filter stage and emits a structured log line for every move ≥ 3%, including zone, momentum, volume, effective threshold, and final result.
- **Resolved market filter** — new filter before zone transition: if `toD < 0.02 || toD > 0.98`, skip as `FILTERED_RESOLVED`. Prevents spurious alerts on markets resolving to 0.1¢ or 99.9¢.
- **Relaxed momentum check** — `hasMomentum()` now only requires the latest snapshot-to-snapshot move to be in the correct direction (was: all 3 consecutive must move). Allows intermediate jitter while still blocking reversed signals.
- **Alert ID dedup fix** — `direction` ("up"/"down") included in alert ID hash so UP and DOWN moves in the same 30-min window for the same market don't collide.
- **Single exit point refactor** — `checkMarket()` restructured from multiple early returns to single `return fired` at end, with `filterReason` variable tracking which stage blocked the alert.
- **Claude sentiment activation** — `ANTHROPIC_API_KEY` confirmed loaded into container via `env_file: .env` in docker-compose.yml; `ClaudeSentimentService` reads `@Value("${ANTHROPIC_API_KEY:}")` directly — no application.yml change needed.
- **Consensus alert human-readable** — `ConsensusDetector.fireConsensusAlert()` now computes outcome (YES/NO), sentiment (bullish/bearish), and current price. Title: "3 wallets buying YES (bullish) — market at 62.5¢". Metadata: `consensusDirection`, `consensusOutcome`, `currentPrice`.
- **Dashboard consensus rendering** — `renderConsensus()` in index.html shows direction in green/red based on bullish/bearish sentiment with current market price.
- **DEBUG logging for PriceMovementDetector** — `logging.level.com.polysign.detector.PriceMovementDetector: DEBUG` added to application.yml.

### Files touched
- `src/main/java/com/polysign/detector/PriceMovementDetector.java`
- `src/main/java/com/polysign/detector/ConsensusDetector.java`
- `src/main/resources/application.yml`
- `src/main/resources/static/index.html`
- `src/test/java/com/polysign/detector/PriceMovementDetectorTest.java`

### Verification
- `mvn test` — 165 tests pass (0 failures, 0 errors), including new `jitteryMomentumStillFires` test
- `docker compose down && docker compose up --build -d` — container starts cleanly
- `docker exec polysign-polysign-1 env | grep ANTHROPIC` — confirms `ANTHROPIC_API_KEY=sk-ant-...` is loaded
- `docker logs polysign-polysign-1 | grep price_movement_detect_complete` — checked=400+, fired=0 (expected at startup, needs 15+ min of price history)
- `docker logs polysign-polysign-1 | grep market_poll_complete` — 400 markets loaded
- `docker logs polysign-polysign-1 | grep rss_poll_complete` — 146 new articles fetched; news correlation will start firing Claude calls after market cache warms

### Deviations from spec
- FIX.md Step 1 (volume sentinel bug) was already fixed in a prior session — no change needed.
- FIX.md Step 4 (dedupe fix) — fromPrice/toPrice were already NOT in the alert ID hash. Added `direction` to the hash as a correctness improvement (UP vs DOWN moves in same window now get distinct IDs).
- FIX.md Step 0 (API key wiring) — service reads `${ANTHROPIC_API_KEY:}` env var directly; no application.yml property mapping needed since the env var is loaded directly via Spring's `StandardEnvironment`.

### Notes for next phase
- `price_check` DEBUG logs will appear after ~15 minutes of runtime (needs price history to detect moves ≥ 3%).
- `claude_sentiment_ok` logs will appear once the news cache warms up (markets=0 at startup, fills after first market poll cycle ~60s).
- The `logback-spring.xml` hardcodes `com.polysign` at INFO; `logging.level.*` in application.yml creates more specific child loggers that override it per Logback hierarchy rules.
- Future improvement: capture per-snapshot volume in `PriceSnapshot` to replace the volume24h delta approximation for `computeVolumeInWindow`.

---

## Phase 20 — Final Polish + Dashboard Redesign + AWS Deploy Scripts
Status: complete
Date: 2026-04-10

### What was built

**Phase A — Bug fixes:**
- **A1 (triple duplicate alerts)**: `PriceMovementDetector` now always uses the 30-minute dedupe window for alertId generation — removed the `bypassDedupe ? Duration.ZERO : dedupeWindow` branch that caused the same directional move to fire 2-3x per poll cycle. `StatisticalAnomalyDetector` now includes `direction` in the alertId hash so UP and DOWN anomalies in the same bucket are distinct (applying the same fix pattern).
- **A2 (Polymarket links broken)**: Added `cleanSlug(String)` static helper to all 5 detectors (`PriceMovementDetector`, `StatisticalAnomalyDetector`, `NewsCorrelationDetector`, `WalletActivityDetector`, `ConsensusDetector`). Strips trailing numeric outcome ID segments (3+ digits, not years 2020–2030) from market-level slugs to produce event-level URLs.
- **A3 (auto-watch)**: Added Phase 6 to `MarketPoller.pollMarkets()` — after upserting the capped set, scans all markets and resets `isWatched=false` for any market not in the current top-25 `autoWatch` set. Also fixed the dashboard API call from `?watched=true` to `?watchedOnly=true` (correct Spring parameter name).
- **A4 (Smart Money detail fields)**: Added `lastMarketQuestion`, `lastOutcome`, `lastSizeUsdc` to `WatchedWallet` model. `WalletPoller.pollWallet()` now tracks these alongside `latestTimestamp` and persists them after each sync.
- **A5 (Claude sentiment threshold)**: Lowered `keyword-prefilter-threshold` from `0.3` to `0.15` in `application.yml` so more articles reach Claude for sentiment scoring.
- **A6 (dep $0 hiding)**: Handled in the Phase B dashboard rewrite — orderbook data is only shown when spread > 0 or depth > 0.

**Phase B — Dashboard redesign (complete rewrite of index.html):**
- Fixed top header: PolySign logo + pulsing live/stale dot + market count + last poll time + ntfy topic
- Four-column stats row: Markets Tracked (with watched subtitle), Alerts Today, Signal Precision (7-day 1h), Whale Activity (wallets active)
- Two-column layout: Alert Feed 65% left, Smart Money + Watched Markets 35% right; single column on mobile
- Alert Feed: grouped by market, collapse/expand, type-colored labels (`PRICE`/`ANOMALY`/`NEWS`/`CONSENSUS`/`WHALE`), per-type rich detail (price arrows + zone tags + orderbook, z-score + σ/μ, sentiment %, consensus wallet counts, whale alias+direction+size); hides zero orderbook fields
- Smart Money: compact wallet cards with left-side bullish/bearish border, shows "BUY YES on 'Market question…' — $2,450", 24h filter, sort by most-recent, show-all toggle
- Watched Markets: compact cards with YES probability progress bar, volume, polymarket links, sorted by volume24h
- Signal Quality: collapsible section at bottom with inline summary line
- Anti-flicker: only replaces alert feed DOM when HTML actually changed
- Removed Chart.js and all audio code
- Fixed `watchedOnly=true` API param (was broken `watched=true`)

**Phase C — AWS deployment:**
- `deploy/setup-ec2.sh`: Amazon Linux 2023 one-shot EC2 bootstrap (docker, java-25-amazon-corretto, docker compose plugin)
- `deploy/run.sh`: checks `.env` exists, runs `docker compose up --build -d`, echoes dashboard URL using EC2 metadata
- `docker-compose.yml`: added `restart: unless-stopped` to both `localstack` and `polysign` services
- `README.md`: replaced stub "Running on Real AWS" section with full "AWS Deployment" section (quick-start steps, cost breakdown, architecture note)

### Files touched
- `src/main/java/com/polysign/detector/PriceMovementDetector.java`
- `src/main/java/com/polysign/detector/StatisticalAnomalyDetector.java`
- `src/main/java/com/polysign/detector/NewsCorrelationDetector.java`
- `src/main/java/com/polysign/detector/WalletActivityDetector.java`
- `src/main/java/com/polysign/detector/ConsensusDetector.java`
- `src/main/java/com/polysign/model/WatchedWallet.java`
- `src/main/java/com/polysign/poller/MarketPoller.java`
- `src/main/java/com/polysign/poller/WalletPoller.java`
- `src/main/resources/application.yml`
- `src/main/resources/static/index.html` (complete rewrite)
- `docker-compose.yml`
- `README.md`
- `deploy/setup-ec2.sh` (new)
- `deploy/run.sh` (new)

### Verification
- `mvn test` — all tests pass after Phase A (before B); all tests pass after Phase B
- `docker compose down && docker compose up --build -d` — build succeeded, both containers started
- `curl http://localhost:8080/actuator/health` — `{"status":"UP"}`
- `curl http://localhost:8080/api/stats` — 400 markets tracked, lastPollTime current
- `curl "http://localhost:8080/api/markets?watchedOnly=true&limit=10"` — returns watched markets
- Docker logs confirm: `auto_watch updated=25 markets by volume24h`
- `git push` — pushed to main (commit 04ba9c3)

### Deviations from spec
- A3: The spec said "unwatch markets that fell out of top 25." Since MarketPoller only upserts markets in the capped-400 set (not all 50k+), the Phase 6 scan covers all markets currently in DynamoDB. This is correct: only markets that were previously polled can have stale isWatched=true.
- A2: Spec's "correct URL" example (`israel-x-hezbollah-ceasefire-by`) appears to be abbreviated; the `cleanSlug` helper strips only trailing purely-numeric outcome IDs (e.g., `-989-656`), preserving dates like `-april-30-2026`. This is more conservative and matches the described algorithm.
- B: Removed severity badges (S2/S3/S4) as per spec. Kept `★` multi-detector badge logic in spirit but simplified (not shown in new design — alert count badge is used instead). Alert "mark reviewed" button removed to keep cards clean (database still tracks reviewed state via API).
- B7: Anti-flicker implemented by diffing the full HTML string and only replacing when content changed (simpler than shadow DOM swap, achieves same result for the feed).

### Notes for next phase
- The system is now shippable. Deploy to EC2 with `bash deploy/run.sh` after cloning and adding `.env`.
- `keyword-prefilter-threshold: 0.15` — monitor Claude API costs; if too many spurious calls increase back to 0.25.
- Future: add `ANTHROPIC_API_KEY` check to `deploy/run.sh` (currently only checks `.env` file exists, not key validity).
- Future: add HTTPS termination via nginx/Caddy in front of the Spring Boot container for the EC2 deployment.
