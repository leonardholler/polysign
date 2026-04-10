# FIX.md — Wire API Key + Diagnostic + Filter Tuning

Read `PROGRESS.md` to reconstruct state, then `REWRITE.md` for context.

---

## Step 0: Wire the Anthropic API key into the app

The Claude sentiment service is dead because no API key is configured. The `.env` file already exists in the project root with `ANTHROPIC_API_KEY=sk-ant-...` and `docker-compose.yml` already has `env_file: .env` so Docker will load it automatically.

**Do NOT modify docker-compose.yml for the API key. It's already handled.**

1. Read `src/main/java/com/polysign/detector/ClaudeSentimentService.java` and find how the API key is loaded (likely `@Value("${anthropic.api-key:}")` or an env var read).
2. Make sure `application.yml` maps the environment variable to the Spring property. If the service reads `${anthropic.api-key}`, add this to `application.yml` if not already present:
```yaml
anthropic:
  api-key: ${ANTHROPIC_API_KEY:}
```
3. If the service checks for blank/null API key and returns null early, that's correct — it just needs the key to actually be set, which it now is via `.env`.
4. If `ClaudeSentimentService` doesn't exist yet or is stubbed out, read `REWRITE.md` Section 6 for the full implementation spec and implement it.

**Do NOT hardcode the API key anywhere.**

---

## Step 1: Fix volume window sentinel bug (likely killing all alerts)

Read `src/main/java/com/polysign/detector/PriceMovementDetector.java`.

Find `computeVolumeInWindow()`. It returns -1.0 as a sentinel when per-snapshot volume data isn't available. Now find where the return value is checked against `minWindowVolume` in `checkMarket()`.

**The bug:** if the code does `if (volumeInWindow < minWindowVolume)` without first checking `volumeInWindow >= 0`, then -1.0 < 5000 evaluates true and EVERY alert gets silently killed.

**The fix:**
```java
double volumeInWindow = computeVolumeInWindow(snapshots);
if (volumeInWindow >= 0 && volumeInWindow < minWindowVolume) {
    // only filter when we HAVE real volume data and it's below threshold
    // ... skip alert
}
// if volumeInWindow == -1.0, skip this filter entirely (no data available)
```

If the code already has the `>= 0` guard, this isn't the bug and you can move on.

---

## Step 2: Relax momentum check

In `hasMomentum()` in PriceMovementDetector, the current logic requires the last 3 consecutive snapshots to ALL move in the alert direction. With 60-second polling, one flat or jittery snapshot kills the signal.

**The fix:** Only require the most recent snapshot-to-snapshot move to be in the correct direction. This still filters dead momentum (latest snapshot reversed) but allows intermediate jitter:

```java
boolean hasMomentum(List<PriceSnapshot> snapshots, String direction) {
    if (snapshots.size() < 3) return true;

    int last = snapshots.size() - 1;
    PriceSnapshot s1 = snapshots.get(last - 1);
    PriceSnapshot s2 = snapshots.get(last);     // most recent

    boolean up = "UP".equals(direction);

    // Only require the latest move to be in the right direction
    // This filters stale/reversed signals but allows intermediate jitter
    return up
        ? s2.getMidpoint().compareTo(s1.getMidpoint()) > 0
        : s2.getMidpoint().compareTo(s1.getMidpoint()) < 0;
}
```

Update `deadMomentumProducesNoAlert` test: it should still fail when the LATEST snapshot reverses direction. Add a new test `jitteryMomentumStillFires`: 3 snapshots where step1 is flat/reversed but step2 is in the correct direction — alert fires.

---

## Step 3: Add diagnostic logging to checkMarket()

This is the most important change for ongoing debugging.

In `checkMarket()`, restructure the filter logic so that instead of early-returning at each filter, you set a `filterReason` string and continue to a single logging+return block at the end.

At the end of checkMarket, add:
```java
if (movePct >= minDeltaP * 100) {
    log.debug("price_check marketId={} question={} from={} to={} movePct={}% zone={} momentum={} volWindow={} effectiveThresh={}% result={}",
        market.getMarketId(),
        market.getQuestion() != null ? market.getQuestion().substring(0, Math.min(50, market.getQuestion().length())) : "?",
        String.format("%.3f", fromD),
        String.format("%.3f", toD),
        String.format("%.1f", movePct),
        zoneTransition,
        hasMomentum(snapshots, direction),
        String.format("%.0f", computeVolumeInWindow(snapshots)),
        String.format("%.1f", effectiveThresholdPct),
        fired ? "ALERT_FIRED" : filterReason);
}
```

Set `filterReason` at each filter stage:
- `"FILTERED_EXTREME_ZONE"` — both < 0.05 or > 0.95
- `"FILTERED_BELOW_THRESHOLD"` — movePct < effectiveThresholdPct
- `"FILTERED_NO_MOMENTUM"` — hasMomentum false
- `"FILTERED_LOW_VOLUME"` — volume below min
- `"FILTERED_DEDUPE"` — deduplication blocked
- `"ALERT_FIRED"` — passed everything

Enable DEBUG logging for this class in `application.yml`:
```yaml
logging:
  level:
    com.polysign.detector.PriceMovementDetector: DEBUG
```

---

## Step 4: Fix duplicate alert firing

The dashboard showed the same alert fire multiple times within minutes for the same move. The dedupe window should prevent this.

1. Read the dedupe logic in `checkMarket()` — look for where `alertIdFactory` creates the deterministic ID and where `attribute_not_exists` is checked
2. Read `AlertIdFactory` to see what fields go into the hash for price alerts
3. If `fromPrice` or `toPrice` is included in the canonical payload hash, a tiny price difference between polls creates two "different" alerts for the same move

**Fix:** Make sure the alert ID for price alerts uses `bucketedTimestamp + marketId + direction` and does NOT include the exact fromPrice/toPrice, so the same directional move in the same time bucket always produces the same ID.

---

## Step 5: Filter out already-resolved markets

The detector is firing on markets that have already resolved (price at 0.1¢ or 99.9¢). Example: ATP Basavareddy vs Dostanli fired 5 times as it dropped from 46¢ to 0.1¢ — the match was already over.

The current extreme-zone filter only skips when BOTH `fromPrice` and `toPrice` are < 0.05 or > 0.95. This misses the case where a market resolves DURING the detection window (from = 0.46, to = 0.001).

**Fix:** In `checkMarket()`, add a filter for the destination price:
```java
// Skip if the market has effectively resolved (destination is at an extreme)
double toD = /* ... the toPrice as a double ... */;
if (toD < 0.02 || toD > 0.98) {
    // Market has resolved or is resolving to a terminal state
    // No actionable signal — you can't trade a market at 0.1¢ or 99.9¢
    filterReason = "FILTERED_RESOLVED";
    // ... skip alert
}
```

The threshold is 0.02/0.98 (not 0.05/0.95) because a market at 2¢ or 98¢ is effectively done — there's no edge in alerting on it.

Add this check BEFORE the zone transition logic so resolved markets never even get classified.

---

## Step 6: Show consensus alert direction and outcome

The consensus alert currently shows: "3 wallets going BUY on market 1860403" — but doesn't say which outcome they're buying (YES or NO) or what that means for the market.

1. Read `src/main/java/com/polysign/detector/WalletActivityDetector.java` — find where consensus alerts are created
2. The `wallet_trades` table has `side` (BUY/SELL) and `outcome` (YES/NO) fields. The consensus alert metadata should include:
   - `consensusDirection`: the direction the wallets agree on (e.g., "BUY YES" or "SELL NO")
   - `consensusOutcome`: what this implies (e.g., "bullish" if buying YES or selling NO, "bearish" if selling YES or buying NO)
   - `currentPrice`: the market's current price at alert time
3. Update the alert title/description to be human-readable:
   - Instead of: `"3 wallets going BUY on market 1860403"`
   - Show: `"3 wallets buying YES (bullish) — market at 62.5¢"`

4. Read `src/main/resources/static/index.html` and update the consensus alert rendering to show:
   - The direction in green (bullish) or red (bearish) text
   - The current market price
   - Which wallets participated (from alert metadata if available)

---

## After all fixes

1. `mvn test` — all tests must pass
2. `docker compose down && docker compose up --build -d`
3. Wait 5 minutes
4. `docker logs polysign 2>&1 | grep "price_check" | tail -50` — verify diagnostic logging works and shows filter reasons
5. `docker logs polysign 2>&1 | grep -i "sentiment\|claude" | tail -20` — verify sentiment calls are being made
6. Update `PROGRESS.md` with Phase 19 (diagnostic logging, momentum relaxation, volume sentinel fix, resolved market filter, dedupe fix, consensus direction display, Claude sentiment activation)

## What NOT to change

- Don't touch the resolution zone thresholds (0.65/0.35) — the Vekic alert proves they work
- Don't touch StatisticalAnomalyDetector
- Don't touch AlertOutcomeEvaluator
- Don't remove the zone transition metadata — working correctly
- Don't modify docker-compose.yml for the API key — env_file handles it
