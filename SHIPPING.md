# SHIPPING.md — Commit, Deploy, Broadcast

Everything needed to ship today after Claude Code finishes the prompts.

---

## 1. Preventing "Co-Authored-By: Claude" in Commits

Claude Code adds a `Co-Authored-By: Claude <noreply@anthropic.com>` trailer to commits by default. Two ways to stop it:

**Option A — Tell Claude Code not to commit at all (recommended).** At the start of each Claude Code session, add this to your opening message:

> "Do not make git commits. Do not stage files. I will review diffs and commit manually."

Then you run:
```bash
git diff              # review everything
git add -A            # stage
git commit -m "feat: add API key auth with hashed storage"
```

You are the sole author. Clean history. You also get a forced code review of every change, which is worth doing anyway.

**Option B — Add a project instruction.** Create or edit `CLAUDE.md` at the repo root:

```markdown
# Repo conventions for Claude Code

- Do not add "Co-Authored-By: Claude" trailers to commit messages.
- Do not add "Generated with Claude Code" or similar footers.
- Use conventional commit prefixes: feat, fix, refactor, test, docs, chore.
```

Claude Code reads `CLAUDE.md` on startup. Either option works. Option A is safer because it forces you to review before commit.

**Fixing commits that already have the trailer:**
```bash
# Last commit only:
git commit --amend

# Multiple commits (interactive rebase):
git rebase -i HEAD~5   # edit the trailers out of each
git push --force-with-lease   # only if nothing else has been pushed on top
```

---

## 2. Commit Strategy for Today's Work

Do NOT commit everything as one giant blob. Each of the 8 prompts is a logical unit. Suggested commit sequence:

```bash
git checkout -b feat/public-feed-and-api

# After Prompt 1:
git add -A && git commit -m "feat(auth): add api_keys table with hashed storage"

# After Prompt 2:
git add -A && git commit -m "feat(auth): add API key authentication filter"

# After Prompt 3:
git add -A && git commit -m "feat(auth): add per-key rate limiting with quota headers"

# After Prompt 4:
git add -A && git commit -m "feat(api): add paginated v1 endpoints with cursor pagination"

# After Prompt 5:
git add -A && git commit -m "feat(api): add OpenAPI/Swagger documentation"

# After Prompt 6:
git add -A && git commit -m "feat(broadcast): add Discord + X public broadcaster"

# After Prompt 7:
git add -A && git commit -m "test(integration): add public API integration test"

# After Prompt 8:
git add -A && git commit -m "docs: reframe as public feed + developer API"

# Push and merge:
git push -u origin feat/public-feed-and-api
```

Merge via PR or fast-forward to main when all commits are green:
```bash
git checkout main
git merge --ff-only feat/public-feed-and-api
git push origin main
```

---

## 3. Deploying to EC2

Your README already has `deploy/setup-ec2.sh` and `deploy/run.sh`. The flow for updates is:

```bash
# On your laptop — push to main
git push origin main

# SSH to EC2
ssh -i key.pem ec2-user@YOUR_IP

# On EC2
cd polysign
git pull origin main

# Add new env vars to .env (see section 4 for values)
nano .env

# Rebuild and restart
bash deploy/run.sh
# or manually:
docker compose down
docker compose up --build -d

# Watch logs for the demo API key (Prompt 1 logs it once on first boot)
docker compose logs -f app | grep "DEMO API KEY"

# Save that key. It will not be shown again.
```

**Health check after deploy:**
```bash
# From your laptop
curl https://polysign.dev/actuator/health
curl https://polysign.dev/api/docs/ui   # should load Swagger
curl -H "X-API-Key: psk_YOUR_DEMO_KEY" https://polysign.dev/api/v1/alerts?limit=5
```

**Rollback if anything breaks:**
```bash
# On EC2
git log --oneline -n 10          # find the last-good commit
git reset --hard <commit-sha>
bash deploy/run.sh
```

---

## 4. Discord Webhook Setup (Free)

Discord webhooks are free, no rate limits you will hit at this volume, no API keys.

1. Create a Discord server (or use an existing one)
2. Create a channel, e.g. `#polysign-signals`
3. Channel settings → Integrations → Webhooks → New Webhook
4. Name it "PolySign", copy the webhook URL (looks like `https://discord.com/api/webhooks/123.../abc...`)
5. Add to EC2 `.env`:
   ```
   DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/123.../abc...
   ```
6. Restart the app. Convergence events will post automatically.

**Cost: $0.**

**Distribution**: post the Discord invite on r/polymarket, your X bio, the polysign.dev dashboard footer. A public channel with real signals gets traction faster than a paid API.

---

## 5. X (Twitter) Setup

**Current pricing (April 2026): X moved to pay-per-use in February 2026.** The old Basic ($200/month) and Pro ($5,000/month) tiers are legacy and not available to new signups. New developers get pay-per-use by default.

**What your bot will cost:**
- Writing a tweet: roughly $0.01 per post
- Your volume: if you post only on signal-strength-3+ convergence events, expect 5–20 posts/day = 150–600/month
- Monthly cost estimate: **$2–$10/month**
- Plus: X gives 20% back in xAI/Grok API credits based on spend — functionally a rebate
- Spending cap: set this in the X Developer Console to hard-limit monthly spend (e.g. $20) so you can't get surprised

**Setup:**
1. Go to `developer.x.com`, sign up with the X account you want the bot to post as
2. Create a Project → Create an App inside it
3. Opt into Pay-Per-Use pricing; set a spending cap ($20/month is safe)
4. Generate OAuth 2.0 credentials with **User context** (required for posting tweets on a user's behalf)
5. Enable write permissions for the app
6. Get a Bearer Token — the XClient class uses this
7. Add to EC2 `.env`:
   ```
   X_BEARER_TOKEN=your_oauth2_user_context_token
   ```
8. Flip the feature flag:
   ```yaml
   polysign.broadcast.x.enabled: true
   ```
9. Restart.

**Do not ship X on day one.** Ship Discord first — it's free, low-risk, and you can validate the broadcast filter is working without burning X credits on a buggy filter. Enable X once you've watched Discord for 24–48 hours and confirmed only real convergence events are firing.

**If X turns out to cost more than expected:** disable the flag, the Discord feed keeps working, the interview story is unchanged.

---

## 6. Total Added Cost

| Item | Cost |
|---|---|
| Discord | $0 |
| X API (pay-per-use, ~10 posts/day) | $3–$10/month |
| EC2 t3.small (already running) | $0 new |
| DynamoDB api_keys table (on-demand) | effectively $0 |
| DNS / Let's Encrypt | $0 |
| **Total new monthly cost** | **~$5–$10** |

---

## 7. Resume Bullet Point

Once deployed and the first few broadcast events have fired, update your resume. Draft:

> **PolySign** — Real-time prediction market signal engine (Java 25, Spring Boot, AWS)
> Built a production event-processing system polling 400+ markets every 60s through four independent anomaly detectors, with idempotent SHA-256-keyed alerts, self-measuring precision via a T+15m / T+1h / T+24h backtesting pipeline, and a public signal feed to Discord and X. Authenticated REST API with SHA-256-hashed keys, per-tier rate limiting (Stripe-style quota headers), and cursor pagination. 126 tests, Resilience4j circuit breakers on every outbound call, structured JSON logging with end-to-end correlation IDs. Live at polysign.dev.

One bullet. Dense. Every phrase is a hook for an interviewer to pull on.

---

## 8. Order of Operations Today

1. Run Claude Code prompts 1–8 in order (add "do not commit" instruction each session)
2. After each, `mvn test` passes → `git add -A && git commit -m "..."`
3. After Prompt 8, `mvn clean verify` fully green
4. Push to GitHub
5. SSH to EC2, `git pull`, update `.env` with DISCORD_WEBHOOK_URL, `bash deploy/run.sh`
6. Grab the demo API key from logs
7. Smoke test the API and the Discord channel
8. Leave X disabled for now
9. Update resume
10. Send to referral

Then: let it run overnight, verify Discord is firing real convergence events, enable X the next day.
