# Claude Code Prompts — PolySign Pivot

## How to Use This

1. Start each Claude Code session by pasting CLAUDE_CONTEXT.md
2. At the top of each session also include: **"Do not make git commits. I will review diffs and commit manually."**
3. Run prompts in order — each builds on the previous
4. After each prompt: `mvn test` must pass, then `git add -A && git commit -m "..."` manually
5. Keep sessions short — one prompt per chat if possible

---

## The Framing (why you built this)

Prediction markets are flooded with unverified heuristics — "whales bought, so you should buy" — that nobody actually measures. PolySign is a truth-seeking engine for those heuristics. The detectors ingest market anomalies; the backtesting loop scores every one of them at T+15m, T+1h, T+24h; the public feed on X publishes signals with their running precision attached. The goal isn't to be right — it's to be measurable. Over time, the account becomes a track record of which prediction-market signals have predictive value and which don't.

This is the frame for your resume, your referral, and your interview. Don't oversell the results (sample sizes are small). Sell the methodology.

---

## Prompt 1: API Key Model + DynamoDB Table

```
Read CLAUDE_CONTEXT.md.

Create API key infrastructure. SECURITY REQUIREMENT: raw API keys NEVER stored in DynamoDB. Only SHA-256 hashes are persisted. Raw key shown to client once at creation, unrecoverable after — AWS/GitHub PAT model.

1. New model: `src/main/java/com/polysign/model/ApiKey.java`
   - PK field: `apiKeyHash` (String) — SHA-256 hex digest
   - Other fields: clientName, tier (enum FREE/PRO), rateLimit (int), createdAt (Instant), active (boolean), keyPrefix (first 8 chars of raw key, for log identification)
   - @DynamoDbBean annotations matching Alert.java / Market.java
   - Do NOT store raw key anywhere

2. New utility: `src/main/java/com/polysign/common/ApiKeyHasher.java`
   - Static `hash(String rawKey)` → SHA-256 hex digest
   - Static `generateRawKey()` → 32 random bytes, base64url-encoded, prefixed `psk_`
   - Unit tests

3. New: `src/main/java/com/polysign/config/ApiKeyRepository.java`
   - DynamoDB Enhanced Client
   - `findByRawKey(String rawKey)` — hashes internally, returns Optional<ApiKey>
   - `save(ApiKey)`, `deactivate(String apiKeyHash)`
   - `createNew(String clientName, Tier tier)` — generates raw key, hashes, saves, returns CreatedApiKey DTO with raw key + metadata

4. Register api_keys table in DynamoConfig.java

5. application.yml:
   polysign.auth.demo-key-enabled: true
   polysign.tables.api-keys: "api_keys"

6. ApiKeyBootstrap on startup: if demo-key-enabled and no demo key exists, createNew("demo-client", Tier.FREE), log raw key at INFO with "SAVE THIS KEY — IT WILL NOT BE SHOWN AGAIN". Idempotent.

7. Add table to bootstrap-aws.sh and LocalStack init (PK=apiKeyHash, string)

8. Unit tests for ApiKeyHasher and ApiKeyRepository
```

## Prompt 2: Authentication Filter

```
Read CLAUDE_CONTEXT.md.

1. `src/main/java/com/polysign/api/ApiKeyAuthFilter.java` extends OncePerRequestFilter
   - Reads `X-API-Key` header (raw key)
   - ApiKeyRepository.findByRawKey()
   - Missing/invalid/inactive → 401 JSON {"error": "Invalid or missing API key", "hint": "Pass your key in the X-API-Key header"}
   - Valid → store ApiKey in request attribute. NEVER log raw key — keyPrefix only
   - EXCLUDE: /, /index.html, /actuator/**, /api/docs/**

2. Register via FilterRegistrationBean targeting /api/v1/**. No full Spring Security.

3. `src/main/java/com/polysign/api/ApiKeyContext.java` — static utility to get current ApiKey from request attribute

4. ApiKeyAuthFilterTest: valid key, missing key, inactive key, excluded paths
```

## Prompt 3: Rate Limiting

```
Read CLAUDE_CONTEXT.md.

1. `src/main/java/com/polysign/api/RateLimitFilter.java`
   - Runs AFTER ApiKeyAuthFilter
   - Resilience4j RateLimiterRegistry keyed by apiKeyHash
   - FREE 60/min, PRO 600/min, lazy creation

2. Response headers on every authenticated response: X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset (unix seconds)

3. On 429: JSON {"error": "Rate limit exceeded", "retryAfter": N}, set Retry-After header + three X-RateLimit-* headers. 429 does NOT count against quota.

4. application.yml: polysign.auth.rate-limits

5. RateLimitFilterTest: FREE blocks after 60, PRO allows more, Remaining decrements, Reset is valid future timestamp, 429 includes all headers

6. Micrometer counter polysign.api.rate_limited (tagged by clientName)
```

## Prompt 4: Versioned B2B Endpoints

```
Read CLAUDE_CONTEXT.md.

Create /api/v1/ controllers, separate from internal controllers.

CURSOR PAGINATION (shared):
- `cursor` param opaque base64url
- Serialize: DynamoDB LastEvaluatedKey → JSON with type-tagged values ({"S":"..."}, {"N":"..."}) → base64url no padding
- Deserialize: reverse, pass to exclusiveStartKey
- Shared utility `src/main/java/com/polysign/api/v1/CursorCodec.java` with encode/decode; unit tests including roundtrip
- Malformed cursor → 400 {"error":"Invalid cursor"} via custom InvalidCursorException in GlobalExceptionHandler. No stack traces leaked.

1. AlertsV1Controller: GET /api/v1/alerts?marketId=&type=&minSeverity=&since=&limit=50&cursor=
   - limit default 50, max 200
   - Response: { data: [...], pagination: { cursor, hasMore }, meta: { requestedAt, clientName } }
   - Include signalStrength and phoneWorthy on each alert

2. SnapshotsV1Controller: GET /api/v1/snapshots?marketId=(required)&since=&limit=100&cursor=
   - limit max 500

3. SignalsV1Controller: GET /api/v1/signals/performance — not paginated, clean DTO

4. MarketsV1Controller: GET /api/v1/markets?category=&minVolume=&limit=&cursor=

5. Generic PaginatedResponse<T> envelope in `src/main/java/com/polysign/api/v1/dto/`

6. Per-controller tests: happy path, missing required params, invalid cursor (400), cursor roundtrip
```

## Prompt 5: OpenAPI Documentation

```
Read CLAUDE_CONTEXT.md.

1. Add springdoc-openapi-starter-webmvc-ui to pom.xml (2.x)

2. @OpenAPIDefinition on main app class: title "PolySign API", description, version "1.0"

3. @Tag/@Operation/@Parameter on v1 controllers. @Schema on v1 DTOs.

4. application.yml:
   springdoc.api-docs.path: /api/docs
   springdoc.swagger-ui.path: /api/docs/ui

5. Exclude /api/docs/** from ApiKeyAuthFilter

6. Verify: mvn spring-boot:run, hit /api/docs/ui
```

## Prompt 6: PublicBroadcaster + Auto-Outcome Reply Threads

```
Read CLAUDE_CONTEXT.md.

Build the public broadcaster. The product identity: a truth-seeking X account that publishes only high-conviction convergence events and then auto-replies to its own posts with the backtested outcome at T+15m, T+1h, and T+24h. Every tweet becomes a self-scoring thread. Over time the account accumulates a public, receipts-first track record of which prediction-market signals have predictive value.

The filter is NOT new logic. It reuses the existing PhoneWorthinessFilter so we have a single source of truth. Additional gate: signalStrength >= 3 (multi-detector convergence only — no precision-gated-critical-alone events on public X).

## Part A: Original tweet broadcaster

1. New: `src/main/java/com/polysign/notification/PublicBroadcaster.java`
   - Parallel consumer on the existing alerts-to-notify SQS queue (separate @SqsListener from NotificationConsumer — document why in a class comment: "separation of concerns, independent failure modes, can fail without killing phone notifications")
   - Gate: alert.phoneWorthy == true AND alert.signalStrength >= polysign.broadcast.min-signal-strength (default 3)
   - Idempotency: use existing alertId. No extra cache needed — the existing phone-notification path's idempotency plus alertId uniqueness is sufficient. If the broadcaster restarts mid-queue, SQS redelivery + alertId dedup prevents double-posts.
   - On broadcast success, write a new DynamoDB record in a new `broadcast_threads` table (see Part C) to persist the X tweetId for later reply-threading

2. New: `src/main/java/com/polysign/notification/XClient.java`
   - POST to X API v2 /2/tweets using OAuth 2.0 user-context bearer token
   - Original tweet format (280 chars max, enforce truncator):
     Line 1: 🎯 {market name, truncated if needed}
     Line 2: Signals: {emoji-per-detector: 📈 price | 📊 stat | 🐋 whale | 📰 news} — strength {N}
     Line 3: Running t1h precision for this combo: {P}% (n={sampleSize})
     Line 4: Scoring this at +15m, +1h, +24h. Thread below.
     Line 5: polysign.dev/#alert/{shortId}
   - Resilience4j circuit breaker `x-api` + retry with exponential backoff
   - Returns the posted tweetId so PublicBroadcaster can store it

## Part B: Auto-outcome reply threads

3. New: `src/main/java/com/polysign/notification/OutcomeReplyPoster.java`
   - Scheduled task runs every minute
   - Queries `broadcast_threads` for threads where:
     * the next horizon (15m / 1h / 24h) is due, AND
     * the corresponding outcome record exists in alert_outcomes, AND
     * we haven't already posted the reply for that horizon
   - For each, compose a reply tweet (in-reply-to the original tweetId) with the outcome:
     Line 1: ⏱ T+{horizon} update
     Line 2: Direction: {CORRECT ✅ | WRONG ❌ | FLAT ⚪️ (|Δ|<0.5pp)}
     Line 3: Move: {+X.Xpp | -X.Xpp} from {fromPrice} → {toPrice}
     Line 4: Updated {detector-combo} precision: {newP}% (n={newSampleSize})
   - Mark the horizon as posted in broadcast_threads
   - Uses XClient with in_reply_to_tweet_id set

## Part C: Persistence for thread tracking

4. New DynamoDB table: `broadcast_threads`
   - PK: alertId (String)
   - Attributes: originalTweetId, originalPostedAt, reply15mTweetId, reply15mPostedAt, reply1hTweetId, reply1hPostedAt, reply24hTweetId, reply24hPostedAt, market name (denormalized for display)
   - Add to DynamoConfig, bootstrap-aws.sh, LocalStack init
   - Enhanced Client model: `src/main/java/com/polysign/model/BroadcastThread.java`
   - Repository: `src/main/java/com/polysign/config/BroadcastThreadRepository.java`

## Part D: Config + feature flags

5. application.yml:
   polysign.broadcast.x.enabled: false    # default off, flip via env var when ready
   polysign.broadcast.x.bearer-token: ${X_BEARER_TOKEN:}
   polysign.broadcast.min-signal-strength: 3
   polysign.broadcast.reply-thread-enabled: true
   polysign.tables.broadcast-threads: "broadcast_threads"

6. .env.example entries (don't commit real secrets):
   X_BEARER_TOKEN=

## Part E: Tests

7. PublicBroadcasterTest:
   - Filters correctly (phoneWorthy=false, or signalStrength<3, both skipped)
   - Calls XClient on a qualifying alert
   - Persists broadcast_threads record with originalTweetId

8. OutcomeReplyPosterTest:
   - Posts +15m reply only after outcome exists
   - Does not re-post an already-posted horizon
   - Handles direction=CORRECT / WRONG / FLAT
   - Composes reply with in_reply_to_tweet_id set to the original tweetId

9. XClientTest:
   - Tweet length <= 280, truncation for long market names
   - Reply includes in_reply_to_tweet_id
   - Circuit breaker wraps the HTTP call

## Part F: Metrics

10. Micrometer counters:
    - polysign.broadcast.posted (tagged by type: original | reply_15m | reply_1h | reply_24h)
    - polysign.broadcast.skipped (tagged by reason: below_threshold | disabled | duplicate | outcome_not_ready)
    - polysign.broadcast.failed (tagged by type and error)

## Why this design

Document in the class Javadoc for PublicBroadcaster:
- Why PhoneWorthinessFilter is the source of truth (single filter, already production-tested via 24/7 phone notifications acting as QA)
- Why signalStrength >= 3 is the public-only extra gate (multi-detector convergence is the rarest + most defensible signal type; avoids publishing precision-gated-critical-alone events which are noisier)
- Why auto-outcome replies are the product (receipts-first; over time the account becomes a public track record nobody else has; aligns with "truth-seeking engine" framing)
```

## Prompt 7: Integration Test

```
Read CLAUDE_CONTEXT.md.

1. `src/test/java/com/polysign/integration/PublicApiIT.java` extends AbstractIntegrationIT
   - Bootstrap creates demo API key
   - GET /api/v1/alerts without X-API-Key → 401
   - GET /api/v1/alerts with invalid key → 401
   - GET /api/v1/alerts with valid demo key → 200, valid JSON envelope
   - Seed alerts via AlertService, query, verify pagination (page 1 → use cursor → page 2, no overlap)
   - Rate limit: burst of 65 requests, verify at least one 429 with correct headers

2. `src/test/java/com/polysign/integration/BroadcasterIT.java` (optional but recommended)
   - Mock X API with a WireMock or MockWebServer
   - Seed an alert that passes PhoneWorthinessFilter with signalStrength=3
   - Verify original tweet posted, broadcast_threads record created with tweetId
   - Seed the T+15m alert_outcome, advance clock, verify reply posted in_reply_to original tweetId

3. mvn verify must pass all existing + new tests
```

## Prompt 8: README + DESIGN.md Rewrite

```
Read CLAUDE_CONTEXT.md.

Rewrite README.md and DESIGN.md to reflect the public-feed + developer-API framing and the truth-seeking-engine thesis.

README.md:
1. Open paragraph — new framing. Paraphrase:
   "PolySign is a truth-seeking engine for prediction market signals. Most of the retail market trades on unverified heuristics ('whales bought, so follow') that nobody actually measures. PolySign ingests market anomalies through four independent detectors, scores every alert at T+15m/T+1h/T+24h against forward price movement, and publishes only high-conviction convergence events to X — each followed by auto-reply outcomes at every horizon. Over time the account becomes a public track record of which signals have predictive value. The product isn't being right. The product is being measurable."
   Keep the read-only disclaimer.
2. Keep all existing Architecture and Engineering Decisions sections — they're strong.
3. Update mermaid diagram: add PublicBroadcaster reading SQS, XClient arrow to X, OutcomeReplyPoster reading broadcast_threads + alert_outcomes, reply arrow back to X. Keep ntfy.sh as secondary sink (the "personal QA channel").
4. New section "Public Feed" before Engineering Decisions: post format, the PhoneWorthinessFilter + signalStrength>=3 gate, the auto-outcome reply thread mechanic. Link to the X handle (placeholder: @PolySignBot or whatever you create).
5. New section "Developer API" after Public Feed: X-API-Key header, rate limits + quota headers, cursor pagination, example curl per v1 endpoint, /api/docs/ui.
6. Move "No auth" from Limitations to Shipped.
7. Honest Limitations update: sample sizes for precision numbers are still small; the account's claimed precision is only as good as the data behind it, and early-cycle numbers will be unstable.

DESIGN.md:
1. New section: "Why a truth-seeking engine, not a signal seller"
   - The retail ecosystem is flooded with unmeasured heuristics
   - Most portfolio projects publishing signals don't score them
   - The auto-reply outcome thread is the differentiator — receipts with every post
2. New section: "Public Broadcaster design"
   - Why a separate SQS consumer (independent failure modes)
   - Why PhoneWorthinessFilter is reused instead of duplicated
   - Why signalStrength>=3 is the public gate specifically
   - Why broadcast_threads is DynamoDB (simple PK=alertId lookup, small writes, durable across restarts)
3. New section: "Developer API design"
   - SHA-256 at rest (AWS/GitHub PAT model)
   - Cursor over offset (DynamoDB LastEvaluatedKey native support)
   - Per-key Resilience4j (lazy, tier-aware)

Voice: direct, technical, opinionated. No marketing copy.
```

---

## Post-Build Checklist

- [ ] `mvn clean verify` — all green
- [ ] `docker compose up --build` — starts clean locally
- [ ] /api/docs/ui loads
- [ ] curl with X-API-Key → 200 with rate limit headers
- [ ] curl without key → 401
- [ ] Manually stub an alert that passes PhoneWorthinessFilter + signalStrength>=3, verify broadcaster would fire (with X disabled — log-only)
- [ ] All commits made manually by you (no Claude co-author trailer)
- [ ] Push, redeploy EC2, verify live
- [ ] Phone notifications still firing (regression check)
- [ ] Leave polysign.broadcast.x.enabled=false for first 48h — phone is the QA channel
- [ ] After 48h clean phone-alert stream, flip X on, watch first real broadcasts + outcome replies
- [ ] Update resume with truth-seeking-engine framing
- [ ] Send to referral

## Later (Post-Ship, Not Today)

- Replying under PolymarketWhales with scored outcomes of *their* posts. High-risk, high-reward. Requires:
  - An established track record on your own account first (2+ weeks, 50+ scored posts)
  - Careful rate limiting to avoid platform manipulation flags
  - Replies that add data, not snark
  - Ideally soft permission from the target account (DM introduction)
  - A separate feature branch, not mixed with today's work
