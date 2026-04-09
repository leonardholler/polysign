# What To Do Next — Step By Step

This walks you through exactly what to do, in order, starting right now. Read it top to bottom; don't skip ahead.

## TODAY (next 30 minutes)

### Step 1 — Replace your `spec.md` with the new version

1. In Finder, open `/Users/leonardholler/polysign`.
2. Rename the existing `spec.md` to `spec.md.v1-backup` (just in case).
3. Take the **`spec.md`** file I gave you (the one named `spec.md`) and copy it into `/Users/leonardholler/polysign/spec.md`.
4. Do the same with **`REMAINING_PHASES.md`** — save it to `/Users/leonardholler/polysign/REMAINING_PHASES.md`, replacing the old one if there is one.
5. Open a terminal in that folder and run:
   ```
   cd /Users/leonardholler/polysign
   git add spec.md REMAINING_PHASES.md
   git commit -m "spec: v2 — add signal quality backtesting and real AWS deploy"
   ```

**That's the file-replacement done.** Claude Code's future sessions will read the new spec in the kickoff ritual and know about Phases 7.5 and 12.

### Step 2 — Finish your current Phase 5 session

You are mid-Phase-5, right? You have the test file written and you're waiting to approve it. Here's what to do:

1. Go back to your open Claude Code session (the one where you wrote `StatisticalAnomalyDetectorTest.java`).
2. Tell it this (copy-paste):

```
Before I approve the tests, two changes based on an updated spec:

1. In tests 4 and 5, change the base series from exactly flat to flat +
   tiny ε-noise (±0.0001) so stddev is nonzero. This removes the hidden
   division-by-zero coupling — the implementation should skip markets
   when stddev is exactly zero (no volatility info yet), and tests 4/5
   shouldn't depend on how that edge case is handled.

2. Add an orderbook depth capture step to the Phase 5 scope: when an alert
   fires (not every poll), call clob.polymarket.com/book?token_id=... once
   via the same Resilience4j WebClient. Compute spreadBps and depthAtMid
   and attach them to the alert metadata. 500ms budget; if it fails, fire
   the alert with null book fields. Add two unit tests: synthetic book with
   known depth, and CLOB failure → alert still fires with null fields.

   This applies to BOTH PriceMovementDetector and StatisticalAnomalyDetector.

Update the test file with these changes, show me the diff, and wait for
my approval before writing any implementation code.
```

3. When it shows you the updated tests, actually read them. Check that tests 4 and 5 have nonzero stddev in the base. Then approve and let it implement.

4. When Phase 5 finishes, `/exit` and run `git log --oneline -10` to confirm the commit landed.

### Step 3 — Create your AWS account (if you don't have one)

You don't need AWS until Phase 12, but billing alarms take 24-48 hours to propagate, so **start this today** so it's ready when you need it.

1. Go to https://aws.amazon.com and click "Create an AWS Account".
2. You'll need a credit card. You won't be charged for anything until you actively create resources in Phase 12.
3. Choose the **Free Tier** plan ("Basic support - Free").
4. Once your account is created, you'll be logged into the AWS Console.

### Step 4 — Set up billing alarms (5 minutes, do this TODAY)

This is the single most important thing you do with AWS before Phase 12. It protects you from runaway bills.

1. In the AWS Console, search for **Billing** in the top search bar and click it.
2. In the left sidebar, click **Billing preferences**.
3. Check both boxes: "Receive PDF invoice by email" and "Receive Free Tier usage alerts".
4. Enter your email. Save.
5. Still in Billing, click **Budgets** in the left sidebar → **Create budget**.
6. Choose "Use a template" → "Monthly cost budget".
7. Set budget amount to **$20**. Email alert at 80% ($16) and 100% ($20).
8. Enter your email. Create budget.
9. Repeat steps 5-8 with a **$40** budget (hard cap — if this fires, something is wrong).

**Now you are protected.** You will get an email if your AWS bill approaches $16, $20, or $40. Since Phase 12 target is $15-30/month, the $20 alert is your "everything is normal" signal and the $40 alert is your "something broke, investigate" signal.

---

## THIS WEEK

### Phase 6 — Wallet Tracking + Consensus Detector

After Phase 5 finishes, open a fresh Claude Code session and run Phase 6 per the prompt in `REMAINING_PHASES.md`.

- Model: `/model sonnet`
- Budget: $2–5
- Should take one evening session.

### Phase 7 — News Ingestion

Same pattern. Sonnet. $2–4. One session.

### Phase 7.5 — Signal Quality (the most important phase) ⭐

**This is the phase that makes your project credible.** Budget extra time and attention here. Do all three checkpoints separately — don't let Claude Code do them all at once.

- Model: `/model sonnet`
- Budget: $4–7
- May take two sessions if the tests take multiple iterations.

After Phase 7.5 is done, let the app run for **at least 24 hours** before touching Phase 8. You want real `alert_outcomes` data to populate the signal quality panel when you build it.

---

## NEXT WEEK

### Phase 8, 9, 10 — Dashboard, Polish, Integration Test

All Sonnet. All one session each. These are mechanical phases.

### Phase 11 — README + DESIGN

- Model: `/model opus`
- Budget: $5–12
- **Do not start this with less than $13 remaining.** Top up first if needed.

This is the phase where you spend real attention and real money. Do it well. Claude Code will write three documents (README, DESIGN, aws-setup); read each one critically and push back when it reads like spec summary rather than engineering defense.

---

## THE WEEK AFTER — Phase 12 (Real AWS Deployment)

**This is when AWS actually costs you money.** Before you start Phase 12:

1. Confirm your $20 and $40 billing alarms are **active** (AWS Billing → Budgets).
2. Decide which URL option you want:
   - **Ugly free URL** (recommended) — $15/mo total, 5 minutes extra setup
   - **Custom domain** — only if you already own one
   - **EC2 instead of Fargate+ALB** — $11/mo total, slightly less "real AWS" but cheaper
3. Install the AWS CLI on your laptop:
   ```
   brew install awscli
   aws configure
   ```
   When it asks for credentials, use an IAM user with AdministratorAccess (create one in AWS Console → IAM → Users → Create user).

Then open a fresh Claude Code session and run the Phase 12 prompt from `REMAINING_PHASES.md`.

**Important**: Phase 12 has two checkpoints. At Checkpoint 1, Claude Code will show you the scripts but NOT run them. You'll run them yourself from your laptop terminal, one at a time, in order (01 → 07). That way you see exactly what's being created in AWS. If anything goes wrong, you have time to stop.

After Checkpoint 1 scripts run successfully, Claude Code's Checkpoint 2 runs the 3 chaos experiments and fills in the README screenshots.

### What Phase 12 costs you while it's live

- ~$15-30/month in AWS charges (depending on URL option)
- Keep it live for **2-3 months** while you're applying to Amazon, then spin it down
- To spin down: there's a `99-teardown.sh` script you should ask Claude Code to generate during Phase 12 CHECKPOINT 1 that deletes everything in reverse order. Run it when you're done. After teardown, monthly cost goes to $0.

**Total AWS cost to have the project live for a full application cycle: ~$30-90.** This is cheap for something that can make the difference on an Amazon intern application.

---

## Summary of the whole plan

| When | What | Cost |
|---|---|---|
| **Today, next 30min** | Replace spec.md, finish Phase 5 tests with ε-noise + orderbook additions, create AWS account + billing alarms | $0 |
| **This week** | Phases 5 (finish), 6, 7, 7.5 | ~$11–21 Claude |
| **Let the app run 24h between Phase 7.5 and Phase 8** to generate signal quality data | — | $0 |
| **Next week** | Phases 8, 9, 10 | ~$7–14 Claude |
| **Week after** | Phase 11 (README + DESIGN) | $5–12 Claude |
| **Week after that** | Phase 12 (AWS deploy) | $3–6 Claude + AWS ongoing |
| **While applying to Amazon** | Keep deployment live | ~$15–30/month AWS |

**Total: ~$28–61 Claude + ~$30–90 AWS** to have a live, interview-ready project for 2-3 months.

**Top up your Claude budget by $15–25 before Phase 11** so you don't run out mid-document.

---

## Common questions

**"What if Phase 5 is already past the test-approval point and the implementation is already being written?"**
Then skip Step 2 in this document. Let Phase 5 finish as-is, and add the orderbook depth to Phase 5.5 (a mini phase): open a fresh session and paste a prompt asking for just the orderbook depth changes to both detectors. It's a 30-line retrofit; probably $0.50.

**"Do I really need the live AWS deployment?"**
Yes. Your own spec's original line was "takes it from a 7 to a 9." A clickable live URL is qualitatively different from a README with screenshots. Skip it only if money is the absolute blocker, and in that case replace it with a 60-second screen recording of the dashboard + chaos experiments running against LocalStack.

**"What if I can't afford Phase 11 on Opus?"**
You can do Phase 11 on Sonnet for ~$2–4, but the quality drop is noticeable and the README is the single document most likely to get you the interview. Scrape together $12 and do it on Opus. It's worth it.

**"What if Claude Code ignores the new spec and tries to do things the v1 way?"**
In the standard kickoff, explicitly say: "Note: spec.md was updated to v2 — make sure you read the latest version, not any cached version. Confirm you see the Signal Quality section and the new Phase 12 before proceeding."

**"Can I skip Phase 7.5?"**
No. It's the biggest credibility upgrade in the whole project. Without it, you have a notification system. With it, you have a notification system with measured precision. The difference is a recruiter's attention.

**"What about OrbStack?"**
Nothing. It's a Docker alternative that Claude Code installed so `docker compose` works on your Mac. It runs quietly in the background and you never interact with it directly. You can forget about it.

---

## One last thing

The single most important thing you can do between now and Phase 11 is **let the app run for real** between phases. Every time you finish a phase, let it run overnight. The longer it's been running when you write the README, the more real numbers you have to put in it. "Precision of 58% across 1,847 fired alerts over 6 weeks of continuous operation" hits very differently than "Precision of 62% across 23 fired alerts over 4 hours". Start accumulating data now.

Good luck. You're building something that is genuinely above the bar for an Amazon intern portfolio. Finish it.
