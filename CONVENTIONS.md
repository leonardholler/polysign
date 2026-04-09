# PolySign — Conventions

Rules that apply to every phase. Claude Code must read this file at the start of every session, alongside `spec.md` and `PROGRESS.md`.

## Stack discipline

- **Java 25** only. No preview features unless `spec.md` explicitly calls for one.
- **Spring Boot 3.5.x** only. Never Spring Boot 4.x (Resilience4j compatibility — see spec).
- **Maven**, single module, Spring Boot parent POM. No Gradle, no Kotlin, no Quarkus, no Micronaut.
- **LocalStack image pinned to `localstack/localstack:3.8`** in both `docker-compose.yml` and Testcontainers. Never `:latest`.
- **Lombok**: latest stable from Maven Central only. Prefer Java `record` for immutable DTOs, value objects, and response bodies. Use Lombok only on mutable entity classes where `@Getter`/`@Setter`/`@Slf4j` actually earn their place.

## Package layout

- Base package: `com.polysign`
- Subpackages per `spec.md` project structure. Do not invent new top-level packages without updating the spec.
- Shared utilities (`CorrelationId`, `Clock` wrapper, `Result<T>`, etc.) live in `com.polysign.common`.

## Code quality

- **No naked `WebClient` calls.** Every outbound HTTP call is wrapped in Resilience4j (retry + circuit breaker, rate limiter where appropriate).
- **No `UUID.randomUUID()` for alert IDs.** Alert IDs are deterministic SHA-256 hashes per the idempotency spec.
- **No catch-and-ignore.** Every catch block either logs at the right level with a correlationId, increments a metric, or rethrows.
- **No `System.out.println`.** SLF4J only.
- **DynamoDB access goes through the Enhanced Client** with annotated bean classes. No low-level `AmazonDynamoDB` calls.
- **Bean Validation on every `@RestController` request body.** Global `@ControllerAdvice` returns RFC 7807 `application/problem+json`.

## Logging

- **Structured JSON logging only**, via `logstash-logback-encoder`.
- Every log line carries a `correlationId`. The ID flows from poll → detector → alert → notification so one event is greppable through the whole pipeline.
- Log levels: `DEBUG` for idempotency no-ops (e.g. `ConditionalCheckFailedException` on duplicate alert), `INFO` for lifecycle events, `WARN` for recoverable failures, `ERROR` only for things that need human attention.

## Testing

- Unit tests use JUnit 5 + Mockito + AssertJ.
- Integration tests use Testcontainers with LocalStack 3.8.
- Detectors get heavy unit test coverage with synthetic inputs before being wired into the scheduler.
- `AlertIdFactory` has dedicated tests proving determinism and non-collision.
- Target ≥70% line coverage on `detector/`, `alert/`, and `processing/` packages. Do not chase coverage on config or DTOs.

## Git hygiene

- **Commit in logical chunks within a phase.** Not one giant commit per phase, not one commit per file.
- Commit messages: `phase N: <what>` (e.g. `phase 3: add AlertIdFactory with determinism tests`).
- `.gitignore` excludes: `.env`, `target/`, `.idea/`, `*.iml`, `localstack_data/`, any secrets.
- Before every commit: `mvn -q compile`, and `mvn -q test` if tests exist in the touched packages.

## Phase discipline

- **Stop at phase boundaries.** Do not start phase N+1 in a phase N session, even if there is time left.
- At the end of every phase, update `PROGRESS.md` with: what was built, files touched, verification result, any deviations from spec and why.
- If the spec is ambiguous or contradicts itself, stop and ask — do not guess and proceed.
- If a phase goes sideways structurally, prefer `git reset --hard` to the phase start over patching.

## Things that are never okay

- Trading code, wallet signing libraries, or anything with wallet write access. This is a read-only monitoring tool. Full stop.
- Inventing real wallet addresses for the seed watchlist. Placeholders only.
- Hardcoding the ntfy topic or any other secret into committed code. Use `application.yml` + `.env`.
- Skipping the Testcontainers integration test in Phase 10. It is the single most valuable test in the project.
