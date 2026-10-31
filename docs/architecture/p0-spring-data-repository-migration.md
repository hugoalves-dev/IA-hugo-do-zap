# P0: Spring Data repository migration for IA-hugo-do-zap

Tracking issue: [#24](https://github.com/hugoalves-dev/IA-hugo-do-zap/issues/24)

Status: **DRAFT / PHASE 0 ONLY**. This file describes an implementation contract. **No JDBC persistence has been replaced by this document. Do not merge as a completed migration.**

## Verified current state (2026-10-31)

- Java 25, Spring Boot 4.0.9, PostgreSQL 17 in Testcontainers, Flyway migrations V1–V33.
- `pom.xml` declares `spring-boot-starter-jdbc` and does not declare Spring Data JDBC or Spring Data JPA.
- Classes including `JdbcInboxRepository`, `JdbcJobRepository`, `ChatStateService`, `PersistedPolicyRepository`, `SimulationService`, `BudgetService`, `OperationalState`, `ShadowDecisionQuery`, `CallbackTokenService` and `DecisionRuntimeGate` call `JdbcTemplate` directly.
- Existing `JobRepository` / `InboxRepository` interfaces are application abstractions, **not** Spring Data repository interfaces.
- Many SQL operations implement PostgreSQL-specific concurrency, idempotency and fail-closed behaviors. A mechanical `JdbcTemplate -> JpaRepository` substitution is unsafe.

## Architectural decision

**Prefer Spring Data JDBC repositories**, not JPA/Hibernate by default. This codebase relies on SQL-oriented aggregate boundaries and PostgreSQL features, and does not currently need an ORM session/lazy loading. Spring Data JDBC gives typed Spring Data repositories without introducing JPA-specific persistence behavior.

Use the Spring Boot-managed `spring-boot-starter-data-jdbc` version. Keep Flyway as the **single schema owner**. Do not add Hibernate DDL generation or a second persistence database. Entity records/classes must map the *existing* column names, primary keys, JSONB, timestamptz, and enum values correctly.

Map ordinary CRUD and simple projections using `CrudRepository` / Spring Data query methods and `@Query`. Isolate specialized operations in dedicated repository interfaces/fragments with named SQL and integration tests; preserving SQL within a repository is preferable to breaking an atomic transaction. Spring Data JDBC's aggregate replacement semantics on `save` require particular care: avoid naively saving nested aggregate graphs.

## Execution stages

1. **Inventory + safety baseline.** List each SQL entry point, caller, table, transaction and lock scope. Record a test baseline with `mvn -B -ntp verify` on Java 25/PostgreSQL 17. No application behavior changes in this phase.
2. **Data-layer foundation.** Add Spring Data JDBC; introduce a coherent repository package and typed read/write models, transaction boundaries, and conversions only where necessary. Verify Spring Boot wiring, Flyway ordering and PostgreSQL startup before replacing any SQL.
3. **Simple aggregates first.** Convert `holiday_calendar`, `availability_overrides`, `global_policy`, `policy_profiles`, and simple read models. Preserve fallback values, holidays precedence, timezone, aliases and partial overrides.
4. **Identity + inbox.** Convert contact identity and chat state with tests for LID/PN/JID aliases, merged identities, duplicates and snapshot consistency.
5. **Critical queues/outbox.** Convert repositories behind the existing abstractions while retaining row locks, `SKIP LOCKED`, `ON CONFLICT`, `RETURNING`, idempotency constraints, retries and no-double-send guarantees. Prefer custom repository SQL over read-modify-write.
6. **AI + simulation + administration.** Convert budget reservations, consent, cooldowns, durable batch lifecycle, Telegram callbacks and emergency state. Verify that emergency latch, opt-in consent, kill switch, Shadow Mode, TTL and budget are still fail-closed.
7. **Final cleanup.** Remove dead JDBC constructors/services, prohibit direct SQL in Controllers/business Services by a checked rule, keep justified native queries in repositories, update docs and tests.

Each stage should be delivered as a **small independently tested PR** linked to issue #24, rather than landing all high-risk persistence changes in one deploy.

## Non-negotiable acceptance tests

- Fresh PostgreSQL + Flyway V1–V33+ and upgrade from existing schema: same behavior, no destructive migration.
- Ingest, archive/unarchive, contact resolution/alias collision, notification windows and n8n webhook: no behavior change.
- Duplicate webhooks/messages, concurrent claims, retries, expired leases, restart/recovery, no lost work, no double WhatsApp/Telegram send.
- Budget reservation reconciliation, consent revocation, retention, template authorization, cooldown, emergency kill switch and Shadow Mode isolation.
- Atomic writes remain atomic under concurrency; tests must not silently replace lock behavior with load/save.
- `mvn -B -ntp verify` green in GitHub Actions; no production `.env` changes, no new real-action enablement, no merge/deploy until reviewed.

## Review gates

- **This draft currently contains no production changes.** Do not call this migration complete.
- PR #21 (Shadow Mode kill-switch hotfix) must be reviewed and validated independently, without rebasing this migration over an unverified hotfix.
- Before merging a persistence stage, document changed tables/mappings, raw SQL exceptions, data upgrade compatibility, CI run, deployment and rollback plan.

Official reference: https://docs.spring.io/spring-data/relational/reference/jdbc.html
