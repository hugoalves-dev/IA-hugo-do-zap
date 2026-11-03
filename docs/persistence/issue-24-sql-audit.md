# Issue #24 SQL migration inventory (2026-11-02)

Baseline audited: PR #28 at `1ba61ef3d0baa3cd5747de6d274dd4f28b642fad`.
This is a **work-in-progress inventory**, not a declaration that the release acceptance criteria have passed.

Updated through the 2026-11-03 PR continuation; source of truth is the current PR head and CI, not the historical baseline.

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
- Message snapshots/receipts: custom repository, dedup/retention and pending notification upsert (CI #41497050549, 468 tests green)

- Telegram webhook ingress: Spring Data JDBC `TelegramWebhookDataRepository.insertIfAbsent`, preserving atomic `ON CONFLICT` (CI #41499750493, 472 tests green)
- Telegram webhook worker: `TelegramWebhookInboxRepository` encapsulates `FOR UPDATE SKIP LOCKED`, failure backoff, completion and retention; ACK only after commit (CI #41499750493)
- Telegram admin polling: `TelegramAdminPollingRepository` keeps the session-scoped advisory lock and durable offset mutations, with handler+offset transaction unchanged (CI #41501108752, 474 tests green)
- Telegram confirmation tokens: `CallbackTokenLedgerRepository` encapsulates `UPDATE ... RETURNING`, hash-only storage and exactly-once consumption (CI #41499750493)
- Real-action eligibility: Spring Data JDBC `DecisionEligibilityDataRepository` preserves production-mode, AI-availability and confidence checks, defaulting to deny (CI #41499750493)
- Simulation scheduler due-time metric: Spring Data JDBC `SimulationBatchDueRepository`; observability failures still do not block processing (CI #41500191945 green)

- Evolution webhook processing: `EvolutionJobEnvelopeRepository` owns locked envelope read, post-lock lease/expiry validation, expired payload scrubbing, and quarantine. `EvolutionJobProcessor` remains the transaction and message-processing coordinator ([CI #41506619110](https://github.com/hugoalves-dev/IA-hugo-do-zap/actions/runs/41506619110): 475 tests green)
- Telegram admin commands: `TelegramAdminPersistenceRepository` contains availability, profiles, templates, holidays, budget, safety-toggle and audit SQL. The service still owns confirmation and the transaction; read-only decision details remain in `ShadowDecisionQuery`.
- Identity contact and merge: `IdentityMergeRepository` now encapsulates canonical alias locks/reads, contact-name precedence persistence, snapshot/pending rekey, quarantine, and processed-message dedup. `IdentityService` keeps merge policy and aggregate decisions without owning JDBC statements (validated by CI #41641682019).
- Personal reference evidence: `PersonalReferenceEvidenceRepository` provides source-message and owner-message-reference projections; hash verification and the fail-closed proof rule remain in the domain utility.
- Delivery runtime gate: `DecisionRuntimeQueryRepository` owns current-decision and data-consent SQL, while the gate resolves policy/availability through managed services, preserving fail-closed authorization.
- Intelligent alerts: `IntelligentAlertPersistenceRepository` owns advisory locking, outbox verification and evidence queries; `IntelligentAlertService` retains authorization, formatting and transaction orchestration.
- Delivery admission: `DeliveryAdmissionRepository` owns the locked outbox token, chat claim, cooldown and dispatched-attempt persistence, with the existing coordinator transaction and HTTP-outside-transaction boundary intact.
- Notification queue projection: `NotificationQueueQueryRepository` owns due-notification SQL, blocked-group deletion and connection alert generation lookup. Formatting, filtering and outbox transitions stay in `NotificationService` (later full CI runs passed)

- Debounce simulation capture/claim: `DebouncePersistenceRepository` owns PostgreSQL-native aliases, advisory locks, batch scheduling, SKIP LOCKED, retry and retention SQL. `DebounceService` retains serialization, policy window decisions and the original transaction boundaries.

- Simulation decisions: `SimulationPersistenceRepository` owns snapshot reads, transaction-bound advisory locks, production-mode persistence, cooldown writes, template projections and batch completion. `SimulationService` keeps classification calls outside transactions and after-commit audit/metrics.

- WhatsApp action authorization and enqueue: `WhatsAppActionPersistenceRepository` owns advisory locks, queue/idempotency writes, current template authorization and pre-send action verification. `WhatsAppActionService` still owns safety decisions and transaction admission.

- WhatsApp dispatch: `WhatsAppActionDispatchRepository` owns the conditional PENDING→DISPATCHED claim, delivery acknowledgement, cooldown, expiry and status persistence. Dispatcher keeps transport calls outside transactions and never retries UNKNOWN outcomes.

- Legacy SQLite import: `LegacySqliteSnapshotRepository` owns read-only input SQL; `LegacyImportPersistenceRepository` owns PostgreSQL reconciliation, alias collision checks and idempotent upserts. The service retains conflict detection, dry-run and all-or-nothing rollback policy.

- AI classification: `AiClassificationEvaluationRepository` owns claim, evaluation-result CAS, consent and batch-scope SQL; managed `ConversationContextRepository` and `PersonalReferenceEvidenceRepository` own context and reference projections. `AiSimulationClassifier` keeps provider calls outside SQL transactions and revalidates consents before use.

- Telegram read-only decisions: `ShadowDecisionQuery` is explicitly a PostgreSQL-native query repository (read-only projections and retention-filtered previews); it is no longer exposed as an ordinary component with an implicit persistence role.

- Quarantine: `QuarantineEventRepository` now exclusively owns the redacted `quarantined_events` insert, ID normalization and bounded expiry; the legacy `QuarantineWriter` is a SQL-free compatibility bridge.

## Completed Java source SQL-boundary sweep (2026-11-03)

- Reviewed the SQL-executing service, worker, controller, scheduler, AI, notification and legacy-import paths. All remaining PostgreSQL `JdbcTemplate` execution is located in the Spring Data JDBC or custom PostgreSQL repository layer.
- Deleted the non-repository `DecisionEligibilityJdbcReader` adapter. Standalone acceptance tests reuse the `@Query` SQL from `DecisionEligibilityDataRepository`; full Spring Boot/PostgreSQL integration coverage validates the actual Spring Data repository, including denial of shadow decisions.
- Renamed and annotated the SQLite snapshot reader as `LegacySqliteSnapshotRepository` (`@Repository`). The read-only connection, SQLite `query_only` enforcement and legacy schema/retention checks remain unchanged.
- Added a repository-boundary regression test scanning all production Java sources, including unannotated classes, for direct JDBC execution, raw JDBC connections, owned JDBC clients and SQL statement literals.
- SQL in tests and Flyway migrations is intentional. Native SQL inside custom repositories remains necessary for PostgreSQL locks, JSONB, leases and atomic claims/ACKs. This is a **code-structure audit**, not independent authorization to merge or deploy.

## Automated structural boundary gate

- `PersistenceSqlBoundaryTest` scans **all** production Java files (including unannotated helpers), rejects JDBC calls/clients/raw connections and native SQL literals outside repositories, and ensures the legacy eligibility adapter cannot return. Legacy JDBC constructor signatures remain temporarily for tests but must not execute SQL in services.
- The gate is intentionally narrow: syntax-only heuristics do not replace database behavior tests, a complete manual SQL inventory, or code review of repository-native operations.

## Shared dispatch admission and constructor discipline

- `NotificationOutboxRepository`, `WhatsAppActionDispatcher` and `NotificationService` resolve the same Spring-managed `DecisionDeliveryCoordinator` in production, while original standalone-test constructor overloads remain.
- `ConversationContextRepository` is wired explicitly in `PersistenceBeansConfiguration`, avoiding constructor `@Autowired` and allowing the classifier to reuse the managed repository bean. None of these changes alters the PostgreSQL lease/ACK SQL.

## Production dependency injection acceptance

- `EvolutionWebhookControllerTest.productionBeansUseManagedPersistenceAndSharedSafetyGates` verifies that the live Spring Boot context uses managed persistence repositories rather than compatibility JDBC constructors, and that WhatsApp/Telegram real-send paths share the same runtime authorization gate and delivery admission coordinator.

## PostgreSQL integration coverage added during review

- `JdbcInboxRepositoryContractTest`: concurrent identical webhook intake across independent transactions, and rollback of a failed transaction followed by a clean retry; verifies exactly one durable processing job.
- `JobRepositoryTest.leaseRecoveryAfterRepositoryRecreationRejectsStaleWorker`: recreates the repository against existing PostgreSQL state, reclaims an expired lease and verifies the old worker cannot complete it.
- These are regression tests for native repository contracts. They do **not** replace independent review of queue/ACK/UNKNOWN semantics.

## Remaining release gates and justified native repositories

The remaining `Jdbc*` classes are themselves repositories, not SQL in business services. These PostgreSQL-specific operations are **intentional exceptions** under issue #24 and must not be replaced with naive entity `save()` operations. Spring Data JDBC `@Modifying` does not provide arbitrary `INSERT ... RETURNING` projections (see official query-method documentation).

| Existing PostgreSQL repository | Native operation retained | Validation required before merge |
| --- | --- | --- |
| `JdbcInboxRepository` | Atomic `ON CONFLICT DO NOTHING RETURNING id` + creation of READY job in one transaction | Duplicate event, rollback, restart and no double processing |
| `JdbcJobRepository` | `FOR UPDATE SKIP LOCKED`, `UPDATE ... RETURNING`, lease token checks | Competing workers, lease expiry, stale completion and retry |
| `NotificationOutboxRepository` | Locked Telegram rate-limit singleton, `SKIP LOCKED`, token CAS, ACK and pending-generation compare-and-delete | No duplicate sends, ambiguous ACK, rate-limit retry and restart |
| Other custom `@Repository` implementations | Advisory locks, JSONB, conditional upsert / delete, sensitive retention | Existing Testcontainers/PostgreSQL-17 integration suites and independent diff review |

`ShadowDecisionQuery` is an explicitly annotated read-only query repository. `QuarantineWriter` is a SQL-free compatibility bridge to `QuarantineEventRepository`. Standalone-test JDBC constructors remain for compatibility and should only be deleted when tests are moved to managed production constructors; they do not issue SQL in the services.

**Not yet accepted:** an independent reviewer must confirm the complete SQL inventory, Testcontainers concurrency/restart cases, immutable image rollback, and production metrics. The release remains blocked even when CI passes. See [rollout checklist](issue-24-rollout-checklist.md). Leave issue #24 open and the PR as Draft.

## Must-pass release gate

1. Inventory all `JdbcTemplate`, `ConnectionCallback`, `TransactionTemplate` and SQL literals outside repositories, including any new classes.
2. Exercise PostgreSQL 17/Testcontainers under concurrency, crash/restart, duplicate event, stale alias, delayed ACK, and failed provider calls.
3. Green Java 25 `mvn -B -ntp verify`, full GitHub Actions, independent code review.
4. Document production metrics and rollback before an explicitly approved deploy.
5. Leave #24 **open** until every remaining area is implemented and green. **No merge/deploy** for partial work.
