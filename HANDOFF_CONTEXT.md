# PolySign Handoff Context — Paste at Start of New Claude Chats

Copy this entire document as the first message in any new Claude chat (especially Sonnet chats, to avoid burning Opus tokens on context-building). It contains everything another Claude needs to pick up the project mid-stream.

---

## The project in one paragraph

**PolySign** is a real-time event-processing and anomaly-detection system for Polymarket prediction markets, being built as a portfolio project for an Amazon SDE intern application. It polls ~400 active markets every 60 seconds, runs four alert engines (price movement, statistical z-score anomaly, smart-money wallet consensus, news correlation) against the snapshot history, pushes filtered alerts to the user's phone via ntfy.sh, and measures its own signal quality via a backtest subsystem. The project is **read-only and monitoring-only** — it never places trades, never signs transactions, and has zero write access to any wallet. The user makes all trading decisions manually after receiving alerts.

**Tech stack**: Java 25, Spring Boot 3.5.5, AWS SDK v2 (DynamoDB Enhanced Client, SQS, S3, CloudWatch), LocalStack 3.8 pinned for local dev, Testcontainers, JUnit 5 + Mockito + AssertJ, Micrometer + Prometheus (local) + CloudWatch EMF (prod), Resilience4j for retries/circuit-breakers/rate-limiting, Rome for RSS, Jackson, Lombok (sparingly — prefer records), SLF4J + Logback with JSON structured logging, vanilla HTML + Chart.js + Tailwind CDN for the dashboard (no build step), Docker + Docker Compose locally, GitHub Actions for CI, bash + AWS CLI for production deployment. **Do not suggest** Kotlin, Gradle, Quarkus, Kafka, Terraform, CDK, React, or any framework swap.

**Project path**: `/Users/leonardholler/polysign` (the user's laptop). If Claude Code ever asks, use that exact path.

## The user

The user is **Leonard**, applying for Amazon SDE intern roles. He is **early in his software engineering journey** — he knows enough to build this project with Claude Code holding his hand, but he does not know git, terminal, AWS, or deployment conventions deeply. When giving instructions:

- **Give exact commands to copy-paste.** Do not say "navigate to the folder" — say `cd ~/polysign`.
- **Explain what each command does** in one sentence before giving it.
- **Warn him before destructive actions.** `rm`, `git reset`, etc. need explicit "this deletes X" labels.
- **Don't assume he recognizes tools.** He asked "what is this orbstacker thing" about OrbStack that Claude Code installed; assume the same about any AWS service, Docker flag, or Maven plugin.
- **Be direct but warm.** He wants honest assessment, not cheerleading. He also pushes back when confused, so answer the confusion first before moving on.

His **goal for the final product**: receive 10–30 phone notifications per day on the *best* signals (not every alert), with full history in DynamoDB and on a dashboard. He wants to make trading decisions manually; PolySign is a radar, not an auto-pilot.

## Current state (as of end of Phase 5, April 2026)

**Completed phases:**
- Phase 0 — Repo bootstrap
- Phase 1 — Foundation (Spring Boot, Docker, LocalStack 3.8, all 7 base tables, 3 SQS queues + DLQs, S3 bucket, JSON logging, BootstrapRunner idempotent)
- Phase 2 — Market + Price Polling (MarketPoller, PricePoller, Gamma API + CLOB integration, Resilience4j wrapping)
- Phase 2.5, 2.6 — Market volume floors and top-N cap (final set: ~400 most active markets)
- Phase 3 — AlertService + AlertIdFactory (deterministic SHA-256 alertIds, composite-key idempotency with bucketed createdAt, PriceMovementDetector with 9 unit tests, worked idempotency proof in PROGRESS.md with alertId `845f165cde7828da5dd7e7cc649d3f225f783467d500bd0279809e8f1ef9b048`)
- Phase 4 — NotificationConsumer (SQS long-poll, ntfy.sh POST, Resilience4j wrapping, 7 unit tests)
- Phase 4.5 — PriceMovementDetector tuning (added `min-delta-p: 0.03` floor and extreme-zone filter 0.05/0.95 to eliminate tail-zone noise; 4 new unit tests; the story of `0.0045 → 0.0055` noise is a key README anecdote)
- Phase 5 — StatisticalAnomalyDetector (z-score of 1-minute absolute returns over 60min, 12 tests, live verified with a real 4.76σ alert on "Israel x Hezbollah ceasefire by April 30, 2026" at $451k volume) + OrderbookService (extracted as a separate class, spreadBps + depthAtMid captured at alert time for both detectors, 10 new tests, CLOB failures do not block alerts)

**Current test count**: 54 tests, 0 failures (16 PriceMovementDetector, 15 StatisticalAnomalyDetector, 4 OrderbookService, 12 AlertIdFactory, 7 NotificationConsumer).

**Current git status**: all work committed. Latest commits:
- `a0eaa57` — phase 5: PROGRESS.md with live verification
- `5f88067` — phase 5: OrderbookService + book depth capture in both detectors
- `55c6780` — phase 5: StatisticalAnomalyDetector + 12 TDD tests
- `6495b31` — spec: v2 — signal quality backtesting + real AWS deploy
- `754f265` — phase 4.5: PriceMovementDetector tuning

**Remaining phases:**
- **Phase 6** — Wallet Tracking + Consensus Detector (Sonnet, $2–5). Known blocker: CLOB `/trades` returns 401 Unauthorized. Phase 6 must verify the Polymarket data-api endpoints first and likely fall back to Polygon RPC via `eth_getLogs` on the exchange contract. When Claude Code proposes the fallback plan, read it carefully before approving — vague plans are a red flag.
- **Phase 7** — News Ingestion + Correlation (Sonnet, $2–4). RssPoller with Rome, keyword-based matching.
- **Phase 7.5 — Signal Quality Infrastructure (Sonnet, $4–7). STRICT TDD, 3 checkpoints: (1) AlertOutcome schema + SnapshotArchiver, (2) AlertOutcomeEvaluator + ResolutionSweeper with 0.5pp dead zone, (3) SignalPerformanceService + `/api/signals/performance` endpoint. This phase is the biggest credibility upgrade and must not be skipped.**
- **Phase 8** — Dashboard + Signal Strength sort + Signal Quality panel + **Phone-worthiness gate** (Sonnet, $4–7). The phone-worthiness gate is already inline in the Phase 8 prompt in REMAINING_PHASES.md (search for "Phone-worthiness gate (added mid-plan)"). It routes only the best alerts to ntfy while keeping everything in DynamoDB. Rules: consensus always passes, ≥2 detector types within 15min passes, critical severity with measured precision ≥0.60 passes, else blocked. Target: 10–30 phone notifications/day.
- **Phase 9** — DLQs, Metrics, Resilience4j polish (Sonnet, $1–3). Mandatory proofs: DLQ CLI output, prometheus metric grep for each custom metric, circuit-breaker open/close demo against a dead port.
- **Phase 10** — Testcontainers integration test + GitHub Actions CI (Sonnet, $2–4). Mandatory mutation proof: comment out `alertService.create(...)`, show test fails red, revert, show green.
- **Phase 11 — README + DESIGN.md (Opus, $5–12). Do not start with less than $13 remaining. Critical document — do not let it read as a summary of the spec; it must read as a defense of the engineering decisions. Must include: Phase 3 idempotency worked example with the specific alertId above, Phase 4.5 signal-quality story (0.0045 → 0.0055 tail-zone noise fix), Phase 7.5 measured precision numbers, the scaling story, the "what I'd do differently on real AWS" section. 1,800–3,000 words.**
- **Phase 12 — Real AWS Deployment (Sonnet, $3–6 Claude + $15–30/mo AWS). Checkpoint 1: produce numbered bash scripts (01–07) in `deployment/`. User runs them manually from his own terminal — Claude Code does not run them. Checkpoint 2: deploy, run 3 chaos experiments (circuit breaker, DLQ alarm, idempotent restart), capture evidence in `deployment/chaos/`, update README with live URL. Target cost: under $30/mo. The user already has an AWS account and has set $20 + $40 budget alarms.**

## Key architectural decisions (do not second-guess)

1. **Deterministic alert IDs via SHA-256**, not UUIDs. The bucketed createdAt is also part of the composite-key idempotency — this is in DESIGN.md's idempotency section with the `845f165c...` worked example.
2. **Absolute returns, not percentage returns**, for the z-score detector. Prices are probabilities in [0,1]; an absolute change is the meaningful signal; the delta-p floor is already in absolute terms; mixing would be incoherent.
3. **Same delta-p floor (0.03) and extreme-zone filter (0.05/0.95)** apply to every detector, not just PriceMovement. Introduced in Phase 4.5 after the user observed 0.0045 → 0.0055 firing 22% alerts on tail-zone noise.
4. **`OrderbookService` is a single source of truth** for spreadBps and depthAtMid computation. Both detectors call it. Do not suggest inlining it.
5. **The `alerts` table has a composite key (alertId, createdAt)**. The `attribute_not_exists(alertId)` conditional write only works because `createdAt` is also set deterministically (via `AlertIdFactory.bucketedInstant()`), not from `clock.nowIso()`. This is a correctness requirement — every new detector must follow this pattern.
6. **LocalStack pinned to `localstack/localstack:3.8`** in both `docker-compose.yml` and Testcontainers. Newer tags require an auth token. Do not upgrade.
7. **Spring Boot 3.5.x, NOT 4.0**. Spring Boot 4.0 does not yet officially support Resilience4j. Do not upgrade.
8. **Monolithic single-JAR design**. Schedulers, SQS consumers, and the web server all run in one process. Do not suggest splitting.
9. **`alert_outcomes` table (Phase 7.5)** uses PK=alertId, SK=horizon with `attribute_not_exists(horizon)` for idempotency on re-runs. GSI `type-firedAt-index` for per-detector performance queries.
10. **CLOB `/trades` returns 401** — Phase 6 must use Polygon RPC fallback, not the CLOB endpoint. The `/book` endpoint used by Phase 5 is public and works fine.

## Known gotchas

- **`PageIterable.pages()` does not exist** in AWS SDK v2. Use `.items()` which returns a flattened `SdkIterable<T>`.
- **LocalStack 3.8 volume mount must be `/var/lib/localstack`**, not `/tmp/localstack/data`. LocalStack 3.x tries to `rm -rf /tmp/localstack` on startup, which a volume mount blocks.
- **Model classes use explicit getters/setters, not Lombok.** DynamoDB Enhanced Client annotations conflict with Lombok's auto-generated getters.
- **SQS queue URLs are lazily resolved** (first use, not constructor). BootstrapRunner creates queues after bean initialization, so eager resolution fails with `QueueDoesNotExistException`.
- **Package-private visibility for test override hooks**: `querySnapshots`, `captureOrderbook`, `alertsQueueUrl`. The `TestableDetector` subclass pattern from PriceMovementDetectorTest is the template for new detectors.
- **`price_snapshots` TTL is 30 days** (extended from 7 in v2) to allow 24-hour look-ahead backtesting from hot storage.
- **`mvn -q compile` prints nothing on success** — silence is good. This confuses the user; reassure him when he asks.

## Budget state

- User started with ~$53 Claude Code budget.
- Spent through Phase 5: roughly $12–18 (exact unknown).
- Remaining estimated need: $20–40 for Phases 6 through 12.
- **The user was told to top up by $15–25 before Phase 11 specifically** (Opus is expensive and Phase 11 is the most important document in the project).
- AWS costs are separate and outside the Claude budget — the user can cover those himself. Target: under $30/mo during the 2–3 month application window, teardown after.

## How sessions are structured

Every Claude Code session follows this ritual:
1. Fresh session in `~/polysign`. Never reuse stale sessions.
2. First message: `/model sonnet` (or `/model opus` for Phases 5 and 11 only).
3. Second message: the **standard kickoff** — "Read spec.md, CONVENTIONS.md, PROGRESS.md in full. Confirm phase, convention, blockers."
4. Third message: the phase prompt (copied verbatim from REMAINING_PHASES.md).
5. End of phase: `/exit`, `git log --oneline -10` from the terminal to confirm commits landed.

**One phase per session.** Never mix phases.

## How to talk to the user (tone guide)

- He will ask things like "where do I paste this" and "what is this orbstacker thing" — answer simply without condescension.
- When he does something right, say so directly (he finds it reassuring). Don't over-praise.
- When he does something wrong or skips a step, point it out in a sentence — no paragraph of apology or softening.
- He appreciates honest risk assessment. If a phase is dangerous (Phase 12 cost, Phase 7.5 test baseline, Phase 11 quality), tell him clearly.
- Walk-through commands should be **one command per block** with Enter-press implied. Do not chain commands with `&&` unless he asks for that.
- He uses macOS Terminal and OrbStack for Docker. Commands should be bash-compatible.
- His project is at `~/polysign` or `/Users/leonardholler/polysign`. Use the long path when clarity matters.

## The single most important thing

**The user is not just building a portfolio project; he is building the artifact that decides whether he gets an Amazon intern offer.** Phase 11's README/DESIGN and Phase 12's live deployment are the two documents a recruiter will actually look at. Phase 7.5's measured signal quality is what turns the README from "here's what I built" into "here's what I built and here's proof it works." These three phases matter more than every other phase combined. When in doubt about whether to optimize for speed or quality on these, choose quality.

The five best talking points already in the project:
1. The deterministic alertId idempotency bug + fix (Phase 3) with the specific alertId `845f165c...`
2. The 0.0045 → 0.0055 tail-zone noise story + the delta-p floor fix (Phase 4.5)
3. The live 4.76σ Israel/Hezbollah anomaly caught by the statistical detector (Phase 5)
4. The orderbook-depth-attributed backtest (Phase 5 + 7.5)
5. The three chaos experiments with CloudWatch alarms in prod (Phase 12)

These five are the user's interview ammunition. Help him defend them.

---

## What the user needs from *you* right now

Check `PROGRESS.md` at the top of every conversation to confirm the current phase. If the user asks "what do I do next," read `REMAINING_PHASES.md` to find the next phase prompt, then walk him through sending it to a fresh Claude Code session using the ritual above.

If the user asks for a code review, remember: he is not the one writing the code. Claude Code is. Your job is to review **Claude Code's output** before the user approves it, catching subtle bugs (convention mismatches, missing edge cases, duplicated logic) that would cost him tokens downstream.

Good luck. The project is in good shape; don't break it.
