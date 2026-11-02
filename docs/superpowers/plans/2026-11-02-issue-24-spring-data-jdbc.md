# Issue #24 Spring Data JDBC Migration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace routine SQL CRUD with typed Spring Data JDBC repositories while preserving behavior of the production WhatsApp watcher.

**Architecture:** Migrate one aggregate at a time on top of the existing Flyway/PostgreSQL schema. Preserve PostgreSQL-native atomic operations behind repository interfaces; do not move queue, budget, or authorization checks out of transactions.

**Tech Stack:** Java 25, Spring Boot 4.0.9, Spring Data JDBC, PostgreSQL 17, Flyway V1..V34+, Testcontainers, Maven.

**Spec:** [Issue #24](https://github.com/hugoalves-dev/IA-hugo-do-zap/issues/24).

## Global Constraints

- Flyway alone manages schema. No DDL auto-generation, new destructive migrations, production database writes, deployment, or activation of real actions.
- Fail-closed consent, classification, budget, critical alerts, kill switches, shadow mode, and exactly-once safeguards remain untouched.
- Never use an ordinary `save()` when a PostgreSQL upsert, conditional update, locked claim or unique-key guarantee is part of the original semantics.
- Each slice must pass Java 25 `mvn -B -ntp verify` with PostgreSQL 17 Testcontainers, plus CI and a human review.
- Link incremental PRs to #24, but do not use `Closes #24` until the final acceptance checklist is demonstrably complete.

## Review Focus

1. Read/write existing `timestamptz` without timezone shifts; integration test compares exact `Instant`.
2. Concurrent upserts of singleton rows; preserve `ON CONFLICT` and one-row identity.
3. Existing Flyway V1..V34 installation and startup with Spring Data repository scanning.
4. Alias conflicts and uncertain identity remain fail-closed during later migrations.
5. Job/outbox claims, crash/retry and budgets must never duplicate actions or widen permissions.

## Task 1: Foundation and availability-override aggregate

**Files:** `pom.xml`, `policy/AvailabilityOverrideRow.java`, `policy/AvailabilityOverrideDataRepository.java`, `policy/GlobalPolicyRepository.java`, `policy/AvailabilityOverrideDataRepositoryTest.java`.

**Interfaces:** `AvailabilityOverrideDataRepository.findById(Short)` returns `Optional<AvailabilityOverrideRow>`; `upsert(String,OffsetDateTime,OffsetDateTime)` returns affected rows. The existing `GlobalPolicyRepository` facade preserves public methods and legacy explicit-JDBC constructor while the remaining consumers migrate.

- [x] Add Spring Data JDBC dependency without removing the existing JDBC starter.
- [x] Define explicit columns for the existing singleton and keep atomic PostgreSQL `ON CONFLICT` write.
- [x] Add PostgreSQL integration tests for legacy rows, timestamp equality, idempotent update and database constraints.
- [ ] Run `mvn -B -ntp verify` on Java 25; require green CI before considering this slice verified.

## Task 2: Calendar and policy aggregates

**Files:** `policy/HolidayService.java`, `policy/PersistedPolicyRepository.java`, new typed repository/mapping classes, their tests.

- [ ] Introduce read-only holiday query projection preserving attestation, jurisdiction ordering and year constraints (composite key must not be misrepresented as unique single-column ID).
- [ ] Migrate global/profile/override reads, with JSONB conversion and alias-conflict fail-closed tests.
- [ ] Refactor direct `new ...Repository(jdbc)` paths to injected typed repositories after compatibility tests.
- [ ] Run focused tests and full `mvn -B -ntp verify`.

## Task 3: Identity and snapshots

- [ ] Map chat/contact/identity aggregates, preserving tri-state archived, LID/PN/JID aliases and collision handling.
- [ ] Test merge, rename, duplicate messages and concurrent identity changes before removing old SQL.

## Task 4: Inboxes, jobs, notifications and outboxes

- [ ] Keep `FOR UPDATE SKIP LOCKED`, `RETURNING`, deduplication and conditional updates as native queries in custom repositories.
- [ ] Verify restart/retry/crash paths, ordered delivery and no-double-send using Testcontainers.

## Task 5: Budget, simulation, audit, Telegram and operational safeguards

- [ ] Move routine CRUD to repositories with explicit JSONB/enum/timestamp mapping.
- [ ] Prove atomic reservations, consent checks, kill-switch and fail-closed authorization are unchanged.
- [ ] Remove unused legacy repositories and remaining SQL from services/controllers only after use-site replacement.

## Task 6: Release gate and issue closure

- [ ] Run full Java 25 Maven verify and CI with all migrations and PostgreSQL 17.
- [ ] Audit absence of unintentional SQL in services/controllers; document any intentional native SQL.
- [ ] Document metrics, rollback, data compatibility and safe rollout; keep production unchanged until approved.
- [ ] Only the final green, reviewed PR may include `Closes #24`. Do not merge automatically.
