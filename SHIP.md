# SHIP.md — Final Polish, Dashboard Redesign, AWS Deploy

Read `PROGRESS.md` to reconstruct state. This is the last major push before shipping.

Three phases, in order: **A) Fix all remaining bugs**, **B) Redesign the dashboard**, **C) AWS deployment scripts**. Do not skip ahead.

---

# PHASE A: Bug Fixes

---

## A1: Triple duplicate alerts (CRITICAL)

The same price alert fires 2-3x for the same move (e.g., 57.1¢→42.6¢ fires three times with different spread values: 427bps, 1429bps, 814bps). The dedupe is broken.

1. Read `src/main/java/com/polysign/alert/AlertIdFactory.java`
2. Find what fields go into the hash for `price_movement` alerts
3. The problem: `spreadBps` and/or `depthAtMid` and/or exact decimal `fromPrice`/`toPrice` are in the hash. These change between polls even though it's the same move.
4. **Fix**: For price_movement alerts, the canonical payload hash should ONLY contain: `direction` (UP or DOWN). Nothing else. The alertId becomes: `SHA-256(type + marketId + bucketedTimestamp + direction)`. Two polls detecting the same directional move in the same time bucket = identical alertId = DynamoDB `attribute_not_exists` blocks the duplicate.
5. Verify the bucket window is at least 30 minutes for price alerts.
6. Apply the same fix to `statistical_anomaly` alerts if they have a similar issue.

---

## A2: Polymarket links broken for multi-outcome events

Clicking an alert links to e.g. `https://polymarket.com/event/israel-x-hezbollah-ceasefire-by-april-30-2026-989-656` — which 404s. The correct URL is `https://polymarket.com/event/israel-x-hezbollah-ceasefire-by`.

1. Search the codebase for how links are built:
```bash
grep -rn "polymarket.com\|buildLink\|eventSlug\|slug" src/main/java/ src/main/resources/static/ --include="*.java" --include="*.html" | head -30
```
2. Check the Gamma API response: does it include a separate `eventSlug` or `groupItemTitle` field distinct from the market-level `slug`? Read the MarketPoller or wherever Gamma responses are parsed.
3. **Best fix**: If the Gamma API returns an event-level slug, store it as `eventSlug` on the Market model and use it for links.
4. **Fallback fix**: If no event slug is available, strip the numeric outcome suffixes. Polymarket market slugs follow the pattern `{event-slug}-{outcome-id}`. The outcome ID portion is typically the last numeric segment(s). Use: `slug.replaceAll("-\\d+$", "")` — but be careful not to strip meaningful numbers like years (e.g., `ceasefire-by-april-30-2026` should keep `2026`). A safer approach: only strip if the trailing number is 3+ digits and doesn't look like a year (not between 2020-2030).
5. Test by checking a few known markets and verifying the URLs resolve.

---

## A3: No watched markets / auto-watch broken

The dashboard says "No watched markets." The auto-watch feature that watches top 25 markets by volume isn't working.

1. Search for auto-watch logic:
```bash
grep -rn "auto.watch\|autoWatch\|isWatched\|setWatched" src/main/java/ --include="*.java" | head -20
```
2. If auto-watch exists in MarketPoller, check why it's not triggering. Common issues: it runs on a separate schedule that hasn't fired, or it was disabled.
3. If it doesn't exist, add it to the end of the market poll cycle in MarketPoller:
   - After all markets are upserted, query the top 25 by `volume24h`
   - Set `isWatched = true` on those 25
   - Set `isWatched = false` on any previously watched market that fell out of the top 25
   - Log: `"auto_watch updated={n} markets by volume24h"`
4. Verify: after rebuild, the Watched Markets section should populate within 2 minutes.

---

## A4: Smart Money Tracker is useless

Currently shows "BUY" or "SELL" with no context. A trader needs: what market, what outcome (YES/NO), how much money, when.

1. Read the WatchedWallet model and WalletPoller to see what fields are stored
2. Add these fields to WatchedWallet if not present:
   - `lastMarketQuestion` (String) — the question of the market they last traded
   - `lastOutcome` (String) — "YES" or "NO"
   - `lastSizeUsdc` (String) — dollar amount of the trade
3. Populate them in WalletPoller alongside the existing `lastTradeAt` / `recentDirection` logic
4. Update the dashboard Smart Money table (in index.html) to show:
   - **Alias** (keep)
   - **Last Trade**: `"BUY YES on 'Will Trump visit China...' — $2,450 — 3h ago"` — one dense, readable line combining direction + outcome + market + size + time
   - Drop the separate "Direction" and "Trades" columns — consolidate into the one readable line

---

## A5: Verify Claude sentiment is actually working

After startup, wait 10 minutes, then check:
```bash
docker compose logs 2>&1 | grep "claude_sentiment" | tail -20
```

If ZERO `claude_sentiment_call` logs appear after 10 minutes:
1. Check if `ANTHROPIC_API_KEY` is reaching the container:
```bash
docker compose exec polysign env | grep ANTHROPIC
```
2. If the key is there but no calls are made, the keyword pre-filter is blocking everything (no articles score >0.3 against any market). Lower the threshold:
   - In `application.yml`, change `keyword-prefilter-threshold` from `0.3` to `0.15`
   - This lets more articles through to Claude for scoring — Claude will still reject irrelevant ones
3. If the key is NOT there, check that `docker-compose.yml` has `env_file: .env` under the polysign service (it should already)
4. After fixing, verify: `docker compose logs 2>&1 | grep "claude_sentiment_call.*success"` should show calls

---

## A6: `dep $0` on alerts means orderbook capture is failing silently

Many alerts show `dep $0` which means the CLOB book call returned nothing. This isn't critical but makes alerts look broken.

1. Read `src/main/java/com/polysign/api/OrderbookService.java` (or wherever the CLOB `/book` call is made)
2. Check if the CLOB endpoint URL is correct and if the token_id being passed is valid
3. If the CLOB is consistently failing, the circuit breaker may be open. Check:
```bash
docker compose logs 2>&1 | grep -i "clob\|orderbook\|circuit" | tail -20
```
4. If the CLOB is unreliable, update the dashboard to simply not show the `spd` and `dep` fields when they're zero or null — instead of showing `dep $0` which looks like a bug.

---

# PHASE B: Dashboard Redesign

Read the current `src/main/resources/static/index.html` in full before making any changes.

The dashboard needs to go from "developer debug tool" to "clean trading terminal." Still vanilla HTML + Tailwind + inline JS — no React, no build step. But it should look professional.

---

## B1: Layout and visual hierarchy

The page should have this structure top-to-bottom:

### Header bar (slim, fixed top)
- Left: "PolySign" logo text in bold
- Right: connection status (green dot + "Live" when polling, red + "Stale" if no update in 2 min), market count, last poll time

### Four-column stats row (below header)
Cards showing at-a-glance numbers:
1. **Markets Tracked**: `400` with subtitle `25 watched`
2. **Alerts Today**: `23` with subtitle `7 in resolution zone`
3. **Signal Precision**: `—%` (or actual number if data exists) with subtitle `7-day, 1h horizon`
4. **Whale Activity**: `360 trades` with subtitle `45 wallets active`

### Main content: two-column layout on desktop, single column on mobile
- **Left column (65% width)**: Alert Feed (grouped by market)
- **Right column (35% width)**: Smart Money Tracker + Watched Markets (stacked)

### Color scheme
Use a dark theme — dark gray background (`bg-gray-950`), cards in `bg-gray-900`, borders in `bg-gray-800`. Accent colors:
- Green (`text-emerald-400`) for bullish / UP / YES
- Red (`text-red-400`) for bearish / DOWN / NO  
- Blue (`text-blue-400`) for info / links
- Yellow (`text-amber-400`) for warnings
- White (`text-gray-100`) for primary text
- Gray (`text-gray-500`) for secondary text

---

## B2: Alert Feed (left column)

Grouped by market (already implemented), but improve the presentation:

### Market group header (collapsed state)
```
[Polymarket↗] Will Israel x Hezbollah ceasefire hold?          62.8¢  │ 3 alerts (1h) │ latest: price ↓ 4m ago
```
- Market question as a clickable link (fixed URL per A2)
- Current price in large text, colored green if >65¢ or red if <35¢, white if mid-range
- Alert count badge
- Most recent alert type + relative time
- Click to expand

### Market group (expanded state)
Individual alerts stacked vertically, each as a compact card:
```
┌─────────────────────────────────────────────────────────────┐
│ PRICE ↓  57.1¢ → 42.6¢ (25.4%)           mid-range  4m ago │
│ ▼ entered resolution zone    spd 427bps · dep $1,403       │
└─────────────────────────────────────────────────────────────┘
```

For each alert type, show:
- **Price alerts**: `FROM → TO (PCT% ↑/↓)` with zone transition tag and spread/depth (but HIDE spread/depth if both are zero)
- **Statistical anomaly**: `z=3.4 (σ 0.02, μ 0.51)` — the z-score and stats
- **News alerts**: Show the article title (truncated), the sentiment direction + confidence (`bullish 82%` in green or `bearish 74%` in red), and the source. If scoring method is `keyword_fallback`, show `match: 80%` in gray instead.
- **Consensus alerts**: `3 wallets BUY YES (bullish) — $12,450 total` in green/red
- **Wallet alerts**: `whale_01 BUY YES $2,450` — alias, direction, outcome, size

No severity badges (S2/S3/S4) anywhere.

---

## B3: Smart Money Tracker (right column, top)

Replace the current wide table with a compact card list. Each wallet gets a card:

```
┌──────────────────────────────────────┐
│ 🐋 denizz                    38m ago │
│ SELL YES on "Israel ceasefire..."    │
│ $1,200                    105 trades │
└──────────────────────────────────────┘
```

- Alias in bold, relative time on the right
- Second line: `DIRECTION OUTCOME on "market question..."` (truncated to ~35 chars)  
- Third line: size in USDC on left, total trade count on right
- Card border colored green (bullish = BUY YES or SELL NO) or red (bearish)
- Only show wallets that have traded in the last 24 hours. Wallets with "———" (no trades) are hidden.
- Sort by most recent trade first
- Show max 10 wallets, with a "Show all (45)" toggle

---

## B4: Watched Markets (right column, bottom)

Replace the current list with compact cards:

```
┌──────────────────────────────────────┐
│ Will Rory McIlroy win the Masters?   │
│ 39.5¢  ██████████░░░░░░░░  Vol: 254K│
│ Updated 1m ago           [Polymarket↗]│
└──────────────────────────────────────┘
```

- Market question
- Price as text + a thin progress bar (0-100%) showing the YES probability visually
- 24h volume, last update time, Polymarket link
- Sort by volume24h descending

---

## B5: Signal Quality section

Move the signal quality table to a collapsible section at the very bottom. Most of the time the user doesn't need it — it's for periodic review, not constant monitoring.

When collapsed, show one line: `Signal Quality: price 52% · anomaly 61% · consensus —% · news —%`
When expanded, show the full table.

---

## B6: Mobile responsive

- On screens < 768px: single column layout, alert feed on top, smart money + watched markets below
- Cards should be full-width on mobile
- Font sizes slightly smaller on mobile
- The header stats row wraps to 2x2 grid on mobile

---

## B7: Auto-refresh without flicker

The current polling replaces innerHTML which causes the page to flash. Fix:
- Use a diffing approach: build the new HTML in memory, then only update DOM elements that actually changed
- OR: simpler approach — use `display: none` on a shadow container, build new content there, then swap with the visible container in one operation
- Alert feed and smart money should refresh every 10 seconds
- Stats row should refresh every 30 seconds
- Add a subtle pulse animation on the "Live" indicator to show the connection is active

---

## B8: Remove all audio code

Search one more time and remove any remaining audio/sound code:
```bash
grep -rn "Audio\|oscillator\|play()\|beep\|sound" src/main/resources/static/ --include="*.html" --include="*.js"
```
Remove anything found.

---

# PHASE C: AWS Deployment

---

## C1: Create deployment scripts

Create `deploy/setup-ec2.sh`:
```bash
#!/bin/bash
# Run on a fresh Amazon Linux 2023 EC2 instance
set -euo pipefail

sudo yum update -y
sudo yum install -y docker git java-25-amazon-corretto-devel
sudo systemctl start docker
sudo systemctl enable docker
sudo usermod -aG docker $USER

# Docker Compose plugin
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -SL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

echo ""
echo "=== Setup complete ==="
echo "Log out and back in for docker group, then run: bash deploy/run.sh"
```

Create `deploy/run.sh`:
```bash
#!/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

if [ ! -f .env ]; then
    echo "ERROR: Create .env with ANTHROPIC_API_KEY first"
    echo "  printf 'ANTHROPIC_API_KEY=sk-ant-...\n' > .env"
    exit 1
fi

docker compose down 2>/dev/null || true
docker compose up --build -d

echo ""
echo "=== PolySign is live ==="
PUBLIC_IP=$(curl -s --max-time 3 http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || echo "YOUR_IP")
echo "Dashboard: http://${PUBLIC_IP}:8080"
echo "Health:    http://${PUBLIC_IP}:8080/actuator/health"
echo ""
echo "Tail logs: docker compose logs -f"
```

Make both executable:
```bash
chmod +x deploy/setup-ec2.sh deploy/run.sh
```

## C2: Add restart policy to docker-compose.yml

Under both services, add `restart: unless-stopped` so the app survives reboots:
```yaml
localstack:
    restart: unless-stopped
    ...
polysign:
    restart: unless-stopped
    ...
```

## C3: Update README.md deployment section

Add a clear "## Deployment" section to README.md:

```markdown
## AWS Deployment

### Quick start (single EC2 instance)

1. **Launch EC2**: Amazon Linux 2023, `t3.small`, security group allowing TCP 8080 + SSH 22
2. **SSH in**: `ssh -i key.pem ec2-user@YOUR_IP`
3. **Clone and setup**:
   ```bash
   git clone https://github.com/YOUR_USER/polysign.git
   cd polysign
   bash deploy/setup-ec2.sh
   ```
4. **Log out and back in** (for docker group)
5. **Configure and launch**:
   ```bash
   cd polysign
   printf 'ANTHROPIC_API_KEY=your-key\n' > .env
   bash deploy/run.sh
   ```
6. **Dashboard**: `http://YOUR_EC2_IP:8080`

### Cost
- EC2 t3.small: ~$15/month
- Claude API (Sonnet, 5 calls/min cap): ~$5-10/month
- Total: ~$20-25/month

### Architecture note
This runs LocalStack for DynamoDB/SQS/S3 locally on the EC2 instance. For a production deployment, replace LocalStack with real AWS services using the `aws` Spring profile. See DESIGN.md for the full architecture.
```

---

# Implementation order

1. A1 (dedupe fix — stop triple alerts)
2. A2 (Polymarket links)
3. A3 (auto-watch)
4. A4 (Smart Money detail fields)
5. A5 (verify Claude sentiment, lower pre-filter if needed)
6. A6 (hide zero orderbook data)
7. `mvn test` — all tests must pass
8. B1-B8 (complete dashboard redesign — do this as one big index.html rewrite)
9. `mvn test` again (in case dashboard changes broke anything)
10. C1-C3 (deployment scripts)
11. `docker compose down && docker compose up --build -d`
12. Verify everything visually on `http://localhost:8080`
13. `git add -A && git commit -m "phase 20: final polish + dashboard redesign + AWS deploy scripts"`
14. `git push`
15. Update `PROGRESS.md`

## What NOT to change

- Resolution zone thresholds (0.65/0.35)
- StatisticalAnomalyDetector (61% precision)
- AlertOutcomeEvaluator / backtesting pipeline
- Claude sentiment rate limiter (5/min) and cache
- Resilience4j circuit breaker config
- DynamoDB schema, SQS architecture, alert ID scheme (except the hash fix in A1)
