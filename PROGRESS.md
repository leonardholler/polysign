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
