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
