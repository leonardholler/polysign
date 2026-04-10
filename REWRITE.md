# PolySign — Signal Quality Rewrite

Read `PROGRESS.md` to reconstruct state, then `src/main/resources/application.yml`.

This is a significant quality overhaul. Work through each section in order.

---

## Section 1: Debug and fix wallet tracking (CRITICAL — most valuable broken feature)

The Smart Money Tracker on the dashboard shows all dashes — no trades, no timestamps, no direction for any wallet. This means the WalletPoller/WalletActivityDetector pipeline is completely broken. Consensus alerts (the highest-value signal) can never fire.

1. Read `src/main/java/com/polysign/poller/WalletPoller.java`
2. Read `src/main/java/com/polysign/detector/WalletActivityDetector.java`
3. Check the Docker logs for wallet-related errors:
```bash
docker logs polysign 2>&1 | grep -i -E "wallet|trade|sync|401|403|polygon" | tail -50
```
4. Check if any wallet_trades exist in DynamoDB:
```bash
docker exec polysign-localstack-1 awslocal dynamodb scan --table-name wallet_trades --max-items 3
```
5. Check if watched_wallets have lastSyncedAt set:
```bash
docker exec polysign-localstack-1 awslocal dynamodb scan --table-name watched_wallets --max-items 3
```

Based on what you find, fix the pipeline. Common issues:
- The Data API endpoint might return 401 (noted in PROGRESS.md Phase 2 as a known concern)
- The Polygon RPC fallback might not be implemented
- `lastSyncedAt` might never update, causing re-fetch loops
- The wallet addresses might be wrong format

If the Data API is returning 401 and there's no working fallback, implement one using the Polygon RPC (`https://polygon-rpc.com`). The wallet trades need to actually flow for consensus to work.

### Health-check logging for the ingestion pipeline

The wallet tracking failure reveals a fragile ingestion layer with no visibility. After fixing the pipeline, add explicit health-check logging so failures are immediately obvious:

- On every poll cycle, log a structured summary: `wallet_poll_health: {wallets_attempted: N, wallets_succeeded: N, trades_ingested: N, data_api_status: OK|401|TIMEOUT, rpc_fallback_used: true|false}`
- If the Data API returns 401/403 on 3 consecutive cycles, log at WARN level: `"Data API access restricted — all wallet tracking running through Polygon RPC fallback"`
- If BOTH the Data API AND the RPC fallback fail for a wallet, log at ERROR level and increment a `polysign.wallet.pipeline.failures` counter
- If zero trades are ingested across ALL wallets for 3 consecutive cycles, log at ERROR: `"PIPELINE STALL: No wallet trades ingested in {N} consecutive cycles — consensus alerts cannot fire"`

This ensures you never silently lose the wallet pipeline again.

After fixing, verify: `docker logs polysign 2>&1 | grep "wallet_trade" | tail -10` should show trades being ingested. The Smart Money Tracker dashboard should show non-dash values within 2-3 minutes.

---

## Section 2: Redesign price detector for RESOLUTION detection

The current price detector has 43% 1-hour precision — worse than a coinflip. It fires on any large percentage move, which systematically catches the END of momentum spikes right before mean-reversion.

**The goal is not "did the market move?" — it's "is this market resolving toward 0 or 100?"**

Read `src/main/java/com/polysign/detector/PriceMovementDetector.java` and rewrite the core detection logic:

### A) Resolution-direction filter (most important change)

Only fire price alerts when the price is moving TOWARD an extreme:
- **Bullish resolution signal**: current price > 65¢ AND price is moving UP (toward 100¢)
- **Bearish resolution signal**: current price < 35¢ AND price is moving DOWN (toward 0¢)
- **Mid-range moves (35¢-65¢)**: require a MUCH higher threshold (2x the tier threshold) to fire. A move from 45¢ to 53¢ is just noise. A move from 45¢ to 60¢ might be the start of something.

**Important nuance on resolution zone edge:** The edge is NOT "market is at 65¢ therefore buy YES." The edge is detecting the *move into* the resolution zone — new information being priced in with momentum behind it. A market that has sat at 68¢ for two weeks has no signal. A market that jumped from 52¢ to 68¢ in 15 minutes does. The detector should track whether the price *entered* the resolution zone during the detection window (i.e., `fromPrice` was in mid-range and `toPrice` is in resolution zone), and weight these "zone entry" moves more heavily than moves that start and stay within the resolution zone.

Add a `zoneTransition` field to alert metadata:
- `"ENTERED_BULLISH"` — price crossed from mid-range into >65¢
- `"ENTERED_BEARISH"` — price crossed from mid-range into <35¢
- `"DEEPENING_BULLISH"` — was already >65¢ and moved higher
- `"DEEPENING_BEARISH"` — was already <35¢ and moved lower
- `"MID_RANGE"` — both prices in 35¢-65¢ (high threshold applied)

Zone transitions (`ENTERED_*`) should have their percentage threshold lowered by 25% — these are the highest-signal moves.

Add these as configurable thresholds:
```yaml
polysign:
  detectors:
    price:
      resolution-zone-high: 0.65    # Above this, bullish resolution is interesting
      resolution-zone-low: 0.35     # Below this, bearish resolution is interesting
      mid-range-threshold-multiplier: 2.0  # Mid-range moves need 2x the tier threshold
      zone-entry-threshold-discount: 0.25  # Zone transitions get 25% lower threshold
```

### B) Momentum confirmation (stop catching the top of spikes)

Before firing, check that the move has MOMENTUM — not just a single large candle:
- Require at least 3 consecutive snapshots (over ~3 minutes) where each snapshot moves in the same direction as the alert
- If the latest 2 snapshots show the price flattening or reversing, the momentum is dead — don't fire even if the 15-minute window shows a large total move

Implementation: after finding the max move in the window, check the last 3 snapshots. If `snapshot[n].price` vs `snapshot[n-1].price` vs `snapshot[n-2].price` aren't all moving in the predicted direction, skip.

### C) Volume-weighted significance — per-window, not just 24h

The original spec uses a static 24h volume floor ($50k). This is too blunt. A market can have $500k in 24h volume, but the specific 15-minute price spike your detector catches might have occurred on $200 of volume.

**If snapshot-level volume data is available** (i.e., each `price_snapshot` row carries a `volume24h` or similar field that changes between snapshots):
- Compute the volume *delta* across the detection window: `volumeInWindow = latestSnapshot.volume24h - earliestSnapshot.volume24h`. This approximates the volume traded during the move.
- If `volumeInWindow < $5,000`, discard the alert as noise regardless of percentage move.
- If the move is in the resolution zone (>65¢ or <35¢) AND `volumeInWindow > $20,000`, lower the percentage threshold by an additional 25%. Heavy-volume resolution moves deserve extra attention.

**If snapshot-level volume deltas are NOT available** (volume24h is the same across all snapshots in a window), fall back to the original behavior: if the move is in the resolution zone AND `volume24h > tier1 threshold`, lower the percentage threshold by 25%. Add a `// TODO: capture per-snapshot volume deltas to enable per-window volume filtering` comment.

Add config:
```yaml
polysign:
  detectors:
    price:
      min-window-volume: 5000       # Minimum volume during the move window
      high-volume-window-threshold: 20000  # Volume threshold for resolution zone discount
```

### D) Update tests

Update `PriceMovementDetectorTest.java`:
- Add test: mid-range move (50¢→58¢, 16%) should NOT fire at Tier 1 (below 2x threshold of 16%)
- Add test: resolution zone move (80¢→88¢, 10%) SHOULD fire at Tier 1 (above 8%, in resolution zone, moving toward 100)
- Add test: zone entry move (48¢→67¢) SHOULD fire with lowered threshold and metadata `zoneTransition=ENTERED_BULLISH`
- Add test: move lacks momentum (big gap between old and new snapshot but last 3 snapshots flat) should NOT fire
- Add test: large percentage move on negligible window volume (<$5k) should NOT fire (if per-window volume is implemented)
- Keep existing tests working by ensuring test data falls in resolution zones or exceeds mid-range thresholds

---

## Section 3: Kill the dashboard sound (for real this time)

The sound removal from previous sessions didn't work. The project has a Unicode apostrophe path issue.

1. Find the ACTUAL index.html being served:
```bash
find ~/polysign -name "index.html" -path "*/static/*" 2>/dev/null
```
2. For EACH file found, search for audio:
```bash
grep -n -i "audio\|play()\|beep\|ping\|sound\|oscillator\|AudioContext\|createOscillator" <path>
```
3. Remove ALL audio code from ALL copies of index.html found. Use `sed` or direct file editing via bash, not the str_replace tool (which may hit the Unicode path issue).
4. Verify: `grep -r "Audio\|oscillator\|play()" ~/polysign/src/main/resources/static/`  should return nothing.

---

## Section 4: Dashboard UX overhaul

Read whichever `index.html` is actually being served (from Section 3's find command).

The current feed is an unreadable wall of repeated alerts. Make these changes:

### A) Group alerts by market

Instead of a flat chronological list, group alerts by market. Each market gets one collapsible row showing:
- Market question (linked to Polymarket)
- Current price
- Number of alerts in the last hour (badge)
- Most recent alert summary (type + time)
- Expanding the row shows the individual alerts for that market

This single change will transform the dashboard from unusable to useful. The Israel/Hezbollah market currently takes up 60+ rows. It should take up 1 row that says "Israel x Hezbollah ceasefire — 62.8¢ — 47 alerts (1h)" with a click to expand.

### B) Price alert format

For price alerts, show: `81.3¢ → 63.3¢ (22.2% ↓)` — the from→to with direction arrow. The metadata already has `fromPrice` and `toPrice`.

For alerts with the new `zoneTransition` metadata, show a subtle label:
- `ENTERED_BULLISH` → small green tag: `▲ entered resolution zone`
- `ENTERED_BEARISH` → small red tag: `▼ entered resolution zone`
- `DEEPENING_BULLISH` / `DEEPENING_BEARISH` → no extra label
- `MID_RANGE` → muted tag: `mid-range`

### C) Remove all severity badges

S2/S3/S4 is meaningless visual noise. Remove entirely.

### D) Clean news score display

Replace `📰0.80` with a muted text like `match: 80%`. Once Section 6 (Claude sentiment) is live, show the directional sentiment instead: `bullish 0.82` or `bearish 0.74` in green/red text, with the reasoning as a tooltip on hover.

### E) Keep it simple

This is still vanilla HTML + Tailwind. Don't try to make it a React app. Just restructure the rendering logic in the existing inline JS to group by market instead of flat list.

---

## Section 5: Fix ntfy phone notifications

1. Check what PhoneWorthinessFilter is doing:
```bash
docker logs polysign 2>&1 | grep -i -E "phone_worthy|phone_not_worthy|worthiness|ntfy|notification" | tail -30
```
2. Check if ANY notifications were ever sent:
```bash
docker logs polysign 2>&1 | grep -i "ntfy" | tail -20
```
3. The likely issue: `PhoneWorthinessFilter` requires ≥60% precision for precision-gated alerts. Price is at 43%, so all price alerts are blocked. The only path through is multi-detector convergence or consensus — both of which require working wallet tracking (Section 1).

If no notifications are going through at all, temporarily lower the precision gate to 40% so SOME signals reach the phone while precision improves:
```yaml
polysign:
  notification:
    min-precision: 0.40  # was 0.60, lower temporarily
```
Find the actual config key name by reading `PhoneWorthinessFilter.java` and adjust accordingly. Add a TODO comment: `// Raise back to 0.60 once price detector precision improves above 50%`

---

## Section 6: Replace keyword news matching with Claude-powered sentiment analysis

The `NewsCorrelationDetector` uses keyword overlap to match articles to markets and score relevance. This is fundamentally broken for a financial system: "Trump expected to lose key swing state" and "Trump expected to win key swing state" produce the exact same keyword overlap score but carry opposite market implications. Keyword matching cannot determine directionality.

### The change

Replace the keyword overlap scoring in `NewsCorrelationDetector` with a Claude API call (Sonnet) that evaluates the article's sentiment relative to the market's specific resolution criteria.

1. Read `src/main/java/com/polysign/detector/NewsCorrelationDetector.java`
2. Read whatever service handles the keyword matching logic (likely a `NewsMatchingService` or similar)

### Implementation

Keep the existing keyword matching as a **pre-filter** (cheap, fast, no API cost). Only articles that pass the keyword filter get sent to Claude for sentiment analysis. This means the flow becomes:

```
Article arrives → keyword pre-filter (same as today) → if score > 0.3, send to Claude → Claude returns directional sentiment → fire alert with sentiment data
```

Create a new `ClaudeSentimentService` (or add to the existing news service):

```java
// Prompt template — the key is specificity about the market resolution criteria
String prompt = """
    You are evaluating a news article's impact on a prediction market.

    Market question: "%s"
    Market current price: %.1f¢ (probability of YES)
    Article title: "%s"
    Article summary: "%s"

    Respond ONLY with a JSON object, no other text:
    {
      "relevant": true/false,
      "sentiment": float between -1.0 (strongly suggests NO) and +1.0 (strongly suggests YES),
      "confidence": float between 0.0 and 1.0,
      "reasoning": "one sentence explanation"
    }

    If the article is not relevant to this specific market, set relevant=false and sentiment=0.
    """.formatted(marketQuestion, currentPrice * 100, articleTitle, articleSummary);
```

API call setup:
```java
// Use the Anthropic Messages API
// Model: claude-sonnet-4-20250514
// Max tokens: 150 (keep it cheap — we only need the JSON)
// Temperature: 0 (we want deterministic scoring)
// No system prompt needed, the user message is self-contained
```

Parse the JSON response. If `relevant=true` and `|sentiment| >= 0.3` and `confidence >= 0.5`, fire the alert with enriched metadata:
```java
metadata.put("sentimentScore", sentiment);      // e.g., +0.82
metadata.put("sentimentDirection", sentiment > 0 ? "YES" : "NO");
metadata.put("sentimentConfidence", confidence);
metadata.put("sentimentReasoning", reasoning);
metadata.put("keywordScore", originalKeywordScore);  // keep for comparison/backtesting
```

### Config

```yaml
polysign:
  detectors:
    news:
      keyword-prefilter-threshold: 0.3    # minimum keyword score to send to Claude
      sentiment-relevance-threshold: 0.3  # minimum |sentiment| to fire alert
      sentiment-confidence-threshold: 0.5 # minimum confidence to fire alert
      claude-model: "claude-sonnet-4-20250514"
      claude-max-tokens: 150
      claude-timeout-ms: 5000             # hard timeout on the API call
```

### Resilience

- Wrap the Claude API call in the existing Resilience4j retry/circuit-breaker pattern
- If the Claude call fails or times out, **fall back to keyword-only scoring** (same as today). Log at WARN: `"Claude sentiment unavailable, falling back to keyword scoring for article {articleId}"`
- Rate limit: at most 10 Claude calls per minute (news articles don't arrive that fast, but be safe)
- Add a Micrometer counter: `polysign.news.sentiment.calls` (tags: `status=success|fallback|error`)

### Cost control

At ~100 articles/day passing the keyword pre-filter, with ~150 input tokens + 150 output tokens each, this is roughly 30k tokens/day — well under $1/day on Sonnet.

### Tests

Add to `NewsCorrelationDetectorTest.java`:
- Mock the Claude API call (don't hit real API in tests)
- Test: article with `sentiment=+0.8, confidence=0.9` for a YES market → fires alert with `sentimentDirection=YES`
- Test: article with `sentiment=-0.6, confidence=0.7` → fires alert with `sentimentDirection=NO`
- Test: article with `relevant=false` → no alert fired
- Test: Claude API timeout → falls back to keyword scoring, alert fires with keyword score only
- Test: article with high keyword score but `|sentiment| < 0.3` → no alert (Claude overrides keyword noise)

### What this replaces

This does NOT delete the keyword matching code. Keyword matching becomes the cheap pre-filter. Claude becomes the scoring layer. The `score` field in `market_news_matches` should now reflect the Claude sentiment score (or keyword score if Claude was unavailable). Add a `scoringMethod` field: `"claude"` or `"keyword_fallback"`.

---

## Future improvements (NOT for today — document in PROGRESS.md)

These are architecturally sound ideas that should wait until the core system is stable and shipping:

### Dynamic baseline volatility
Calculate a rolling baseline volatility metric per market. The `StatisticalAnomalyDetector` already computes a rolling standard deviation — feed that into the `AlertOutcomeEvaluator` so that a "correct" alert is defined relative to the market's own historical noise band, not a static threshold. A 1pp move on a slow macroeconomic market is significant; a 2pp move on a volatile pop-culture market is noise. **Why not today:** This touches the `AlertOutcomeEvaluator` and backtesting pipeline, which we're explicitly protecting in this rewrite. Do it once the rewritten detectors have 2+ weeks of outcome data.

### Per-snapshot volume capture
If the CLOB API supports it, start capturing trade volume at each snapshot interval so that per-window volume filtering (Section 2C) can use actual intra-window volume instead of approximating from 24h volume deltas. **Why not today:** Requires a data pipeline change (new field on `price_snapshots`, new CLOB endpoint call per snapshot). Do it in the next data model iteration.

---

## Implementation order

1. Section 3 (kill sound — quick, high annoyance)
2. Section 1 (wallet debug — diagnostic first, then fix, add health-check logging)
3. Section 2 (price detector rewrite — zone transitions, momentum, per-window volume)
4. `mvn test` after Section 2
5. Section 5 (ntfy debug — quick after Section 1 context)
6. Section 6 (Claude news sentiment — new service + tests)
7. Section 4 (dashboard grouping + zone transition labels + sentiment display)
8. `mvn verify` at the end
9. `docker compose down && docker compose up --build`
10. Watch for 5 minutes — check alert rate, wallet tracker, sentiment calls, and ntfy logs
11. Update `PROGRESS.md` (include the "Future improvements" items)

## What NOT to change

- Alert ID scheme, DynamoDB schema, SQS architecture
- StatisticalAnomalyDetector (61% precision — it's working, leave it alone)
- AlertOutcomeEvaluator, backtesting pipeline
- Resilience4j circuit breaker config
