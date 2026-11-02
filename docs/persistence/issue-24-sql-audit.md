# Issue #24 SQL migration inventory (2026-11-02)

Baseline audited: PR #28 at `1ba61ef3d0baa3cd5747de6d274dd4f28b642fad`.
This is a **work-in-progress inventory**, not a declaration that every JDBC call has been eliminated.

## Architecture gate

- Routine CRUD: typed Spring Data JDBC repositories where a real Flyway-backed aggregate/identity exists.
- PostgreSQL-native locking/upsert/claim/JSONB/conditional deletion: explicit custom repository methods, sharing the original service transaction. Do **not** replace with naive `CrudRepository.save()`.
- No raw SQL inside services, workers or controllers at completion. Legacy constructor adapters may temporarily accept `JdbcTemplate`, but must not issue statements directly.
- Flyway exclusively owns DDL. No change to production feature flags, sending, database retention, or deployment until all acceptance tests and a rollback plan pass.

## Migrated to date (PR #28)

- Availability override, global policy/profile/alias overrides: Spring Data JDBC
- Archive monitor settings: Spring Data JDBC
- Operational restriction reads: Spring Data JDBC, fail-closed
- Holiday calendar: custom read-only repository because of composite PK
- Connection state: custom repository, `FOR UPDATE` and outbox atomicity preserved
- Retention: custom repository, `SKIP LOCKED` and preview compare-and-swap retained
- Report summaries: custom repository, advisory lock and idempotent outbox preserved
- AI budget ledger: custom repository, `REQUIRES_NEW` and locked reservation/settlement
- Chat state snapshot: custom repository, alias lock and ordered upsert
- Message snapshots/receipts: custom repository, dedup/retention and pending notification upsert (full CI required before acceptance)

## Still requiring migration and compatibility tests

| Area | Files still with SQL in the baseline | Primary correctness requirements |
| --- | --- | --- |
| Identity and contact | `IdentityService`, `LegacyImportService` | Canonical aliases, LID/PN/JID merge, contact-name precedence, collision rejection |
| Ingest/job/outbox | `JdbcInboxRepository`, `JdbcJobRepository`, `NotificationOutboxRepository`, `EvolutionJobProcessor` | Lease/claim, `SKIP LOCKED`, idempotency, restart, ordered processing |
| Notification delivery | `NotificationService`, `IntelligentAlertService`, `WhatsAppActionService`, `WhatsAppActionDispatcher`, `DecisionActionProducer`, `DecisionDeliveryCoordinator`, `DecisionRuntimeGate` | No duplicate sends, consent, fail closed, cancellation, immutable evidence |
| Simulation and AI | `DebounceService`, `SimulationService`, `SimulationScheduler`, `AiSimulationClassifier`, `ConversationContextRepository`, `PersonalReferenceEvidence` | Shadow isolation, retry, bounded context, budget, advisory locks |
| Telegram admin | `AdminCommandService`, `CallbackTokenService`, `ShadowDecisionQuery`, `TelegramUpdatePollingService`, `TelegramWebhookController`, `TelegramWebhookWorker` | Authorize before action, durable inbox, offsets, callback replay prevention |
| Other | `QuarantineWriter`, remaining historical JDBC compatibility facades | Bounded retention, no sensitive payload leaks |

Source of truth for completion: [issue #24](https://github.com/hugoalves-dev/IA-hugo-do-zap/issues/24).

## Must-pass release gate

1. Inventory all `JdbcTemplate`, `ConnectionCallback`, `TransactionTemplate` and SQL literals outside repositories, including any new classes.
2. Exercise PostgreSQL 17/Testcontainers under concurrency, crash/restart, duplicate event, stale alias, delayed ACK, and failed provider calls.
3. Green Java 25 `mvn -B -ntp verify`, full GitHub Actions, independent code review.
4. Document production metrics and rollback before an explicitly approved deploy.
5. Leave #24 **open** until every remaining area is implemented and green. **No merge/deploy** for partial work.
