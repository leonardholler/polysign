# PROGRESS.md — PolySign Pivot

> Living doc. Update at the end of every Claude Code session. Read at the start of every new one.
> Paste this alongside CLAUDE_CONTEXT.md when starting a fresh session.

---

## Current State

**Branch:** `feat/public-feed-and-api`
**Last session ended:** 2026-04-11
**Next prompt to execute:** Prompt 1 — API Key Model + DynamoDB Table
**Current step within that prompt:** Step 4 (DynamoConfig) — model + hasher + repository done, wiring not yet

---

## Prompt Checklist

- [ ] Prompt 1 — API Key Model + DynamoDB Table *(in progress — steps 1-3 done)*
- [ ] Prompt 2 — Authentication Filter
- [ ] Prompt 3 — Rate Limiting
- [ ] Prompt 4 — Versioned B2B Endpoints
- [ ] Prompt 5 — OpenAPI Documentation
- [ ] Prompt 6 — PublicBroadcaster + Outcome Reply Threads
- [ ] Prompt 7 — Integration Test
- [ ] Prompt 8 — README + DESIGN.md Rewrite

---

## Decisions Locked In (binding for all future sessions)

These are conventions discovered or chosen during execution. Future sessions must follow them without re-asking.

- **Table property path:** `polysign.dynamodb.tables.<name>` (not `polysign.tables.<name>`). All new tables follow this convention.
- **Timestamp storage in models:** `String` (ISO-8601), not `Instant`. Matches existing `Alert` / `Market` models. Avoids needing a custom `AttributeConverter`.
- **Timestamp generation:** use `AppClock.nowIso()` from `common/` package. Confirmed: `AppClock` is a `@Component` wrapper around `Clock`, injectable and mockable. Never call `Instant.now()` or `LocalDateTime.now()` directly — breaks testability.
- **Project root path:** `/Users/leonardholler/polysign` (symlink). Never use the iCloud curly-apostrophe path.
- **No git commits from Claude Code.** User commits manually after review.

[Add more as they come up during execution]

---

## Session Log

### Session 2 — 2026-04-11
**Prompt:** 1, steps 1–3
**What happened:**
- Read CLAUDE_CONTEXT.md, CLAUDE_CODE_PROMPTS.md, Alert.java, Market.java, DynamoConfig.java, application.yml, AppClock.java, BootstrapRunner.java, WalletDiscovery.java
- Flagged two spec/codebase inconsistencies before writing any code (property path, timestamp type); user confirmed both — follow existing conventions
- Created Tier enum, ApiKey model, ApiKeyHasher utility, CreatedApiKey DTO, ApiKeyRepository
- ApiKeyHasherTest added

**Files changed:** see table below
**Tests added:** `ApiKeyHasherTest` (6 tests — determinism, SHA-256 vector, uniqueness, prefix)
**`mvn test` status:** not run yet (wiring incomplete — DynamoConfig bean not registered yet)

**Next step when resuming:** Step 4 — add `apiKeysTable` bean to `DynamoConfig.java`, then Step 5 (application.yml), Step 6 (ApiKeyBootstrap), Step 7 (BootstrapRunner table creation + bootstrap-aws.sh), Step 8 (ApiKeyRepositoryTest)

---

## Known Risks / Open Questions

- [ ] `ApiKeyRepositoryTest` will need to mock `DynamoDbTable<ApiKey>`. Check `AlertOutcomeEvaluatorTest` or similar for the Mockito pattern used with Enhanced Client tables before writing.
- [ ] `bootstrap-aws.sh` location not yet confirmed — find it before Step 7b.
- [ ] Bootstrap log format: `BootstrapRunner` uses SLF4J structured args. The "SAVE THIS KEY" line should use a distinctive log message key (e.g. `demo_api_key_created`) so it's greppable in production logs.

---

## Files Created / Modified So Far

*(Populate as Claude Code creates them. Keep newest last.)*

| Prompt | File | Status |
|---|---|---|
| 1 | `src/main/java/com/polysign/model/Tier.java` | created |
| 1 | `src/main/java/com/polysign/model/ApiKey.java` | created |
| 1 | `src/main/java/com/polysign/common/ApiKeyHasher.java` | created |
| 1 | `src/main/java/com/polysign/config/CreatedApiKey.java` | created |
| 1 | `src/main/java/com/polysign/config/ApiKeyRepository.java` | created |
| 1 | `src/test/java/com/polysign/common/ApiKeyHasherTest.java` | created |

---

## Smoke Test Commands

Run these periodically to catch regressions:

```bash
# Compile only (fast)
mvn compile

# Unit tests
mvn test

# Full verify including integration tests (slow — end of prompt only)
mvn clean verify

# Local run
docker compose -f docker-compose.yml -f docker-compose.local.yml up --build

# Health check after local run
curl http://localhost:8080/actuator/health
```

---

## How to Resume a Session

Paste this at the top of a new Claude Code chat:

```
Read CLAUDE_CONTEXT.md, CLAUDE_CODE_PROMPTS.md, and PROGRESS.md.

PROGRESS.md tells you where we are. Do not re-ask decisions already locked in under "Decisions Locked In". Do not re-do completed prompts.

Three rules for this session:
1. Do not make git commits. I will commit manually.
2. Execute one step at a time. After each meaningful change, stop and check in with me before proceeding.
3. Escalate on ambiguity — do not guess. If PROGRESS.md doesn't have the answer, ask me.

Now tell me in 2-3 sentences what state we're in and what the next step is. Wait for my go-ahead before writing any code.
```
