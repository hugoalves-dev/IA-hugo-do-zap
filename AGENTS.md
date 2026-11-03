# AGENTS.md — IA Hugo do Zap

## Purpose and source of truth

This repository implements a personal WhatsApp archived-chat watcher and controlled automation service. It receives WhatsApp events through Evolution API, persists operational state in PostgreSQL, and delivers notifications and administrative interactions through the Telegram Bot API. Safety, data privacy, and preservation of running production state take priority over convenience.

Before changing code, inspect the current implementation, tests, `README.md`, `pom.xml`, `compose.yaml`, `.github/workflows/backend.yml`, and applicable runbooks. The code and current configuration are the source of truth; historical specifications, plans, and the legacy TypeScript README sections are not proof that a feature is active. Do not assume the state of a running VPS from repository contents.

## Current stack and layout

- Active runtime: Java 25, Spring Boot 4, Maven, Spring Data JDBC, JDBC repositories where PostgreSQL semantics are required, Flyway, PostgreSQL 17, Docker Compose.
- WhatsApp integration: Evolution API 2.x, authenticated per-instance webhook, separate PostgreSQL database and credentials for Evolution.
- Telegram: direct Bot API transport, notification outbox, private administrator commands, and either polling or a dedicated HTTPS webhook. Do not assume n8n is in the current production delivery path.
- AI classification: OpenRouter is the primary provider; Gemini is an optional technical-failure fallback. External classification is subject to consent, privacy, budgets, gates, and explicitly configured opt-ins.
- Primary Java sources: `src/main/java/dev/hugo/watcher/` and its packages.
- Java tests: `src/test/java/`. Database and concurrency integration tests use PostgreSQL through Testcontainers.
- Database migrations: `src/main/resources/db/migration/`.
- Runtime configuration: `src/main/resources/application.properties`, `.env.example`, `compose.yaml`.
- CI/CD: `.github/workflows/backend.yml` and `scripts/deploy-vps.sh`.
- Legacy rollback/reference implementation: `src/`, `tests/`, `package.json` (TypeScript/Baileys/SQLite). Do not treat it as the active Docker runtime or delete it without a separately approved retirement plan.
- Relevant operational references: `docs/telegram-webhook-migration.md`, `docs/local-ai-activation-checklist.md`, and `docs/persistence/issue-24-rollout-checklist.md`.

## Working approach

1. Start by checking `git status`, the current branch, the latest commit, and the relevant files. Preserve pre-existing changes; never overwrite or discard another person's work.
2. Identify the smallest safe change and the invariants it touches. Prefer existing Spring Boot facilities and established project abstractions over new libraries, frameworks, background services, or architectural layers.
3. Keep controllers, workers, and schedulers focused on transport/orchestration. Keep business decisions in their existing services and database access in repositories.
4. Add or update regression tests for bug fixes, including negative paths, restarts, concurrency, and authorization whenever relevant.
5. Run targeted tests during implementation and the complete verification gates before proposing a production release. If a gate cannot run, report precisely which one and why.
6. Explain in Brazilian Portuguese what changed, why, which tests ran, and what remains unverified. Do not report a deployment or live behavior as verified without observable evidence.

## Logging and exception handling

- Use **SLF4J** (`org.slf4j.Logger` and `org.slf4j.LoggerFactory`) for application logging in Java code. Prefer the existing Spring Boot logging configuration; do not add a competing logging implementation or new dependency just to log messages.
- Declare a class-level logger in code that emits logs, for example:

  ```java
  private static final Logger log = LoggerFactory.getLogger(MyService.class);
  ```

  Import `org.slf4j.Logger` and `org.slf4j.LoggerFactory`. Do not introduce `System.out.println`, `System.err.println`, `printStackTrace()`, or ad hoc `java.util.logging`/`System.Logger` calls as substitutes for operational logs. When modifying an existing class, replace relevant print-based diagnostics with SLF4J rather than expanding inconsistent patterns.
- Use parameterized messages (`log.info("Processed batch {}", batchId)`) rather than string concatenation or eager formatting. Use `TRACE`/`DEBUG` for diagnostics, `INFO` for meaningful lifecycle events, `WARN` for recoverable anomalies, and `ERROR` for failures requiring attention; avoid noisy per-message production logs.
- For unexpected exceptions, include a short, useful description and pass the actual `Throwable` as the final argument (for example, `log.error("Failed to process job {}", jobId, exception)`) so the stack trace is retained. Do not log only `exception.getMessage()` or silently swallow an exception.
- **Logging does not replace error handling.** Preserve propagation, retries, transaction rollback, and fail-closed decisions. Log at the layer that handles a failure; avoid logging and rethrowing the same exception at every layer, which creates duplicated stack traces.
- Log safe operational context such as an event name, opaque correlation ID, processing state, and elapsed time. Never log raw WhatsApp message text, phone numbers, raw JIDs/LIDs, chat identifiers, credentials, tokens, Telegram payloads, provider prompts/responses, or database connection strings. Redact or omit sensitive data from exceptions when necessary.
- Do not use `catch (Exception)` merely to hide failures or turn them into a successful outcome. Tests should verify that error paths remain observable and that safety gates continue to fail closed.

## Persistence and transaction invariants

- Flyway exclusively owns production schema changes. Add new, forward-compatible versioned migrations; never silently rewrite an already-applied migration or enable Hibernate schema generation.
- Routine aggregate CRUD may use typed Spring Data JDBC repositories. PostgreSQL-native operations, including `FOR UPDATE SKIP LOCKED`, advisory locks, `ON CONFLICT`, `RETURNING`, JSONB, conditional updates, and compare-and-swap, belong in explicit repository methods.
- Do not place raw SQL or direct `JdbcTemplate`/connection execution inside controllers, services, workers, schedulers, or utilities. Preserve the `PersistenceSqlBoundaryTest` contract.
- Preserve transaction boundaries around queue claims, leases, idempotency, identity merges, authorization, cost reservations, callback consumption, and outbox admission. Never replace an atomic database operation with read-then-write or naive `save()` logic.
- External HTTP calls must not be made while holding database transactions or locks unless the existing, reviewed contract explicitly requires it.
- Treat duplicate webhook deliveries, stale leases, process crashes, retries, and delayed acknowledgements as normal operating conditions. Respect deduplication keys and compare-and-swap/lease ownership checks.
- Do not delete, truncate, reset, or recreate production databases, tables, volumes, sessions, message queues, or migration history to make a failing test/deploy pass.
- Preserve current retention and redaction policies, particularly the seven-day maximum for retained sensitive message excerpts. Never log full messages, access tokens, secrets, phone numbers, or raw JIDs unless an explicitly reviewed requirement allows a safely minimized representation.

## WhatsApp and notification behavior

- Process only eligible new messages from chats whose archived state is known and `archived=true`. Unknown state must not be guessed or treated as archived. Ignore own messages, status/protocol events, and other ineligible events according to existing filters.
- Preserve group-specific rules, suppression policies, business-hour handling, batching, and read-state queue clearing. Default work-hour timezone is `America/Sao_Paulo`, Monday–Friday, 08:00–12:00 and 13:30–17:30, unless current configuration says otherwise.
- Resolve identities using the existing canonical alias model. Do not conflate `@lid`, phone-number JIDs, usernames, and display names; do not create WhatsApp links from an unverified `@lid` or guessed phone number. Group links and personal links must respect the supported identity contract.
- Preserve notification/outbox durability. A Telegram delivery may duplicate after an ambiguous failure; never claim exactly-once delivery without evidence.
- An HTTP 2xx response from Evolution is acceptance, not confirmed WhatsApp delivery. Keep ambiguous WhatsApp results `UNKNOWN`; do not automatically retry an unknown send or bypass its idempotency and cooldown gates.
- Keep Telegram notification formatting, HTML escaping, message-length limits, and the existing `[🤖 HUGO ZAP BOT]: ` administrative/automatic-response identity rules intact. Persisted templates must continue satisfying the existing disclosure constraints.

## Security and automation safety

- Treat all WhatsApp messages, webhooks, provider responses, and Telegram commands as untrusted input. Never allow model output or incoming text to grant permissions, change policies, choose unrestricted tools, or enable real sends.
- Preserve the existing fail-closed checks for chat consent, scope, configuration, templates, availability, personal-reference evidence, cooldowns, cost limits, current decision status, and the persistent emergency lock.
- Shadow/simulation mode must remain side-effect-free with respect to WhatsApp sends and real intelligent alerts. Enabling classification or simulation never implicitly authorizes real actions.
- Real WhatsApp sends require their explicit, independent environment flags and runtime approvals. Do not turn on `WATCHER_DECISION_PRODUCER_REAL_MODE_ENABLED`, `WATCHER_WHATSAPP_ACTIONS_REAL_SEND_ENABLED`, `WATCHER_WHATSAPP_ACTIONS_EVOLUTION_ENABLED`, intelligent alerts, automatic replies, or disable `WATCHER_AUTOMATION_KILL_SWITCH_ENABLED` as part of routine implementation or tests.
- The `/emergencia` persistent stop must continue working without AI; restarting or deploying must not clear it.
- OpenRouter requests must maintain the configured privacy/route constraints, including ZDR and no data collection where enforced. Keep input minimized, validate structured output, and fail closed on timeouts, invalid results, unsupported routes, exhausted budgets, or uncertain classifications. No broad, silent fallback to another provider.
- Separate secrets and authorization contexts for Evolution API, Evolution-to-watcher webhook, Telegram Bot API, Telegram administrator, and Telegram webhook. Never substitute one credential for another.
- Telegram administration must be restricted to the authorized user and private chat. Polling and webhook consumers must never be active simultaneously. For public ingress, expose only the intended authenticated webhook route; do not publish Actuator or the Evolution Manager.
- Never commit `.env`, `.env.local`, tokens, keys, QR codes, session files, database dumps, or other private runtime data. Do not print credentials through `docker compose config` or verbose logs.

## Tests and local validation

Use the project's existing tools and test structure; do not invent commands that are absent from the repository.

```bash
python3 -B -m unittest discover -s tests/ci -p 'test*.py'
mvn -B -ntp verify
```

For focused Java tests, use `mvn -B -ntp -Dtest=RelevantTestClass test` and then run the complete suite before release. Testcontainers integration tests require a working Docker daemon and compatible PostgreSQL test environment. If Docker is unavailable, distinguish unrun integration tests from passing tests.

For Compose-only validation, when a private `.env` exists and validation is authorized:

```bash
docker compose --env-file .env config --quiet
```

Never run a test against the live production database, send messages to real WhatsApp contacts, invoke paid AI classification, or mutate live Telegram settings unless the task explicitly authorizes that operational action.

## Production and deployment rules

**This repository is production-connected. A push to `main` triggers CI, publishes an immutable GHCR image, and automatically deploys the `watcher` to the VPS.** Treat `git push origin main` as a production operation.

- Do not commit, push, merge, trigger workflows, deploy, edit production `.env`, change feature flags, or perform database maintenance unless the user's current task explicitly calls for the relevant action. Preparing a code change does not by itself authorize production deployment.
- For unfinished or still-unapproved work, prefer a dedicated branch and pull request. If the user explicitly requests a production release, verify the requested scope, pass release gates, and follow the existing CI/CD workflow. Respect any open rollout checklist or draft-PR restrictions for the affected work.
- Do not bypass GitHub Actions by copying JARs, running arbitrary live migrations, or directly rebuilding/restarting containers unless the user specifically authorizes a manual recovery procedure.
- Existing deployment only updates the `watcher` through an image pinned by digest. Preserve the external `infra_network`, existing PostgreSQL services, separate database credentials, Evolution instance and volume, Telegram state, `.env`, and Compose ownership markers.
- Never run `docker compose down`, `docker compose down -v`, `--remove-orphans`, `docker system prune`, or force-recreate unrelated services on the VPS. Do not restart Evolution to deploy Java changes.
- Before an explicitly authorized risky release, verify current CI, data-backup strategy, deployed immutable image digest, rollback instructions, and the relevant issue-specific acceptance gates.
- After an authorized deploy, check GitHub Actions status, the `watcher` container health, `/actuator/health/readiness`, startup/migration logs, queue/outbox state, and relevant Prometheus metrics. A healthy HTTP endpoint alone does not prove functional correctness.
- A rollback must preserve PostgreSQL data and migration history. Prefer restoring the previous immutable application image after a reviewed compatibility check; never blindly reverse Flyway migrations. The deploy script's healthcheck rollback does not cover late functional regressions.

## Review checklist

In reviews and before commits, actively flag:

- Any path that can send to WhatsApp without explicit, current authorization or that retries an `UNKNOWN` outcome.
- Any change that weakens archive-only eligibility, identity correctness, privacy, retention, or emergency controls.
- SQL outside repositories, unsafe transaction changes, lost idempotency, missing lease checks, or race conditions.
- Secrets in source/logs, public exposure of internal APIs, or Telegram authorization bypass.
- Unreviewed production flags, destructive Compose commands, incompatible migrations, or accidental `main` deployments.
- Missing negative-path and Testcontainers tests for a change touching durability or delivery.

When reporting results, distinguish **implemented**, **tested**, **committed**, **pushed**, and **deployed/observed**. Never imply one follows from another automatically.
