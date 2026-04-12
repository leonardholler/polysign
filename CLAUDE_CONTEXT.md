# PolySign Pivot — Architecture Context

> Paste this file at the start of every Claude Code chat. It replaces reading the full codebase.

## What PolySign Is

Real-time anomaly detection system for Polymarket prediction markets. Spring Boot 3.5.5, Java 25, DynamoDB (8 tables), SQS (3 queues + 3 DLQs), S3. Deployed on EC2 t3.small, live at polysign.dev.

Polls 400+ markets every 60s → 4 detectors (price threshold, statistical anomaly, whale consensus, news correlation) → alerts → SQS → PhoneWorthinessFilter → ntfy.sh push notifications. Backtesting pipeline scores every alert at T+15m, T+1h, T+24h against actual price movement.

## The Pivot (what we're building today)

**Dual output: public signal feed + developer API.** The product is a public feed of high-convergence prediction-market signals with measured precision, published to X and Discord. The developer API is how serious consumers integrate the same data programmatically.

Honest interview framing: "Real-time signal system with idempotent event processing and self-measuring precision, shipped as a public feed for the Polymarket community with an authenticated API for developers." Real users, real engineering.

## Existing Code Layout

```
src/main/java/com/polysign/
├── config/        AwsConfig, DynamoConfig, HttpConfig, SchedulingConfig, BootstrapRunner, WalletBootstrap, RssProperties
├── common/        CorrelationId, AppClock, AppStats, CategoryClassifier, Result<T>
├── model/         Market, PriceSnapshot, Article, MarketNewsMatch, WatchedWallet, WalletTrade, Alert, AlertOutcome
├── poller/        MarketPoller, PricePoller, WalletPoller, RssPoller
├── detector/      PriceMovementDetector, StatisticalAnomalyDetector, WalletActivityDetector, ConsensusDetector, NewsCorrelationDetector, OrderbookService
├── alert/         AlertService, AlertIdFactory
├── backtest/      SnapshotArchiver, AlertOutcomeEvaluator, ResolutionSweeper, SignalPerformanceService
├── notification/  NotificationConsumer, PhoneWorthinessFilter
├── processing/    KeywordExtractor, NewsMatcher, NewsConsumer, UrlCanonicalizer
├── api/           MarketController, AlertController, WalletController, StatsController, SignalPerformanceController, AlertDto, GlobalExceptionHandler
├── metrics/       SqsQueueMetrics, SignalQualityMetrics
```

Tests: 126 total (121 unit + 5 integration), JUnit 5 + Mockito + Testcontainers/LocalStack.

## Existing DynamoDB Tables

| Table | PK | SK |
|---|---|---|
| markets | marketId | — |
| price_snapshots | marketId | timestamp |
| articles | articleId | — |
| market_news_matches | matchId | — |
| watched_wallets | address | — |
| wallet_trades | address | txHash |
| alerts | alertId | createdAt |
| alert_outcomes | alertId | horizon |

## What We're Adding (in order)

### 1. API Keys table + model (hashed storage)
- New DynamoDB table `api_keys` (PK=apiKeyHash). SHA-256 hashes only — raw key shown once at creation.
- ApiKeyHasher, ApiKey model, ApiKeyRepository.
- Demo key logged once on first boot.

### 2. API Key Auth Filter
- OncePerRequestFilter on /api/v1/**. Logs keyPrefix only. Excludes /actuator, /api/docs, /, /index.html.

### 3. Rate Limiting
- Per-key Resilience4j limiter. FREE 60/min, PRO 600/min.
- Response headers on every authenticated response: X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset.
- 429 with Retry-After.

### 4. Paginated v1 Endpoints
- /api/v1/alerts, /api/v1/snapshots, /api/v1/signals/performance, /api/v1/markets
- Cursor pagination: base64url(JSON(DynamoDB LastEvaluatedKey)) via shared CursorCodec.
- Malformed cursors → 400.

### 5. OpenAPI/Swagger
- springdoc-openapi at /api/docs/ui

### 6. PublicBroadcaster — the actual product
- New component reads from `alerts-to-notify` SQS queue, filters for high-convergence events (signal strength >= 3 OR consensus auto-pass), posts to Discord webhook and X.
- Post format: market name, which detectors converged, current precision for that detector combo, link to dashboard.
- Idempotent: reuses existing alertId to dedupe so restarts don't double-post.
- Feature flags: `polysign.broadcast.discord.enabled`, `polysign.broadcast.x.enabled`. Ship with Discord on, X optional.
- Resilience4j circuit breaker on each outbound webhook.

### 7. README + DESIGN.md rewrite
- Reframe as "public signal feed with measured precision, plus developer API"
- Document auth, rate limits, pagination, broadcast format
- Update architecture diagram to show X/Discord outputs

## Conventions to Follow
- Deterministic IDs (SHA-256 pattern from AlertIdFactory)
- Resilience4j on all external calls (X and Discord webhooks included)
- Structured JSON logging with correlationId
- Tests: unit-test every new class, integration-test the auth flow
- DynamoDB Enhanced Client (SDK v2), not raw low-level calls
- application.yml for all config values, never hardcoded
- NEVER log raw API keys or X/Discord secrets — logs use prefixes only
