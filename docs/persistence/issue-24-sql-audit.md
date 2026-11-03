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
- Message snapshots/receipts: custom repository, dedup/retention and pending notification upsert (CI #41497050549, 468 tests green)

- Telegram webhook ingress: Spring Data JDBC `TelegramWebhookDataRepository.insertIfAbsent`, preserving atomic `ON CONFLICT` (CI #41499750493, 472 tests green)
- Telegram webhook worker: `TelegramWebhookInboxRepository` encapsulates `FOR UPDATE SKIP LOCKED`, failure backoff, completion and retention; ACK only after commit (CI #41499750493)
- Telegram admin polling: `TelegramAdminPollingRepository` keeps the session-scoped advisory lock and durable offset mutations, with handler+offset transaction unchanged (CI #41501108752, 474 tests green)
- Telegram confirmation tokens: `CallbackTokenLedgerRepository` encapsulates `UPDATE ... RETURNING`, hash-only storage and exactly-once consumption (CI #41499750493)
- Real-action eligibility: Spring Data JDBC `DecisionEligibilityDataRepository` preserves production-mode, AI-availability and confidence checks, defaulting to deny (CI #41499750493)
- Simulation scheduler due-time metric: Spring Data JDBC `SimulationBatchDueRepository`; observability failures still do not block processing (CI #41500191945 green)

- Evolution webhook processing: `EvolutionJobEnvelopeRepository` owns locked envelope read, post-lock lease/expiry validation, expired payload scrubbing, and quarantine. `EvolutionJobProcessor` remains the transaction and message-processing coordinator ([CI #41506619110](https://github.com/hugoalves-dev/IA-hugo-do-zap/actions/runs/41506619110): 475 tests green)
- Telegram admin commands: `TelegramAdminPersistenceRepository` contains availability, profiles, templates, holidays, budget, safety-toggle and audit SQL. The service still owns confirmation and the transaction; read-only decision details remain in `ShadowDecisionQuery`.
- Identity contact and merge: `IdentityMergeRepository` now encapsulates canonical alias locks/reads, contact-name precedence persistence, snapshot/pending rekey, quarantine, and processed-message dedup. `IdentityService` keeps merge policy and aggregate decisions without owning JDBC statements (subject to full CI).
- Personal reference evidence: `PersonalReferenceEvidenceRepository` provides source-message and owner-message-reference projections; hash verification and the fail-closed proof rule remain in the domain utility.
- Delivery runtime gate: `DecisionRuntimeQueryRepository` owns current-decision and data-consent SQL, while the gate resolves policy/availability through managed services, preserving fail-closed authorization.
- Intelligent alerts: `IntelligentAlertPersistenceRepository` owns advisory locking, outbox verification and evidence queries; `IntelligentAlertService` retains authorization, formatting and transaction orchestration.
- Delivery admission: `DeliveryAdmissionRepository` owns the locked outbox token, chat claim, cooldown and dispatched-attempt persistence, with the existing coordinator transaction and HTTP-outside-transaction boundary intact.
- Notification queue projection: `NotificationQueueQueryRepository` owns due-notification SQL, blocked-group deletion and connection alert generation lookup. Formatting, filtering and outbox transitions stay in `NotificationService` ([CI #41507350751](https://github.com/hugoalves-dev/IA-hugo-do-zap/actions/runs/41507350751): pending)

- Debounce simulation capture/claim: `DebouncePersistenceRepository` owns PostgreSQL-native aliases, advisory locks, batch scheduling, SKIP LOCKED, retry and retention SQL. `DebounceService` retains serialization, policy window decisions and the original transaction boundaries.

## Still requiring migration and compatibility tests

| Area | Files still with SQL in the baseline | Primary correctness requirements |
| --- | --- | --- |
| Identity and contact | `IdentityService`, `LegacyImportService` | Canonical aliases, LID/PN/JID merge, contact-name precedence, collision rejection |
| Ingest/job/outbox | `JdbcInboxRepository`, `JdbcJobRepository`, `NotificationOutboxRepository` | Lease/claim, `SKIP LOCKED`, idempotency, restart, ordered processing |
| Notification delivery | `WhatsAppActionService`, `WhatsAppActionDispatcher`, `DecisionRuntimeGate` | No duplicate sends, consent, fail closed, cancellation, immutable evidence |
| Simulation and AI | `SimulationService`, `AiSimulationClassifier`, `ConversationContextRepository`, `PersonalReferenceEvidence` | Shadow isolation, retry, bounded context, budget, advisory locks |
| Telegram admin | `ShadowDecisionQuery` | Authorize before action, durable inbox, offsets, callback replay prevention |
| Other | `QuarantineWriter`, remaining historical JDBC compatibility facades | Bounded retention, no sensitive payload leaks |

Source of truth for completion: [issue #24](https://github.com/hugoalves-dev/IA-hugo-do-zap/issues/24).

## Must-pass release gate

1. Inventory all `JdbcTemplate`, `ConnectionCallback`, `TransactionTemplate` and SQL literals outside repositories, including any new classes.
2. Exercise PostgreSQL 17/Testcontainers under concurrency, crash/restart, duplicate event, stale alias, delayed ACK, and failed provider calls.
3. Green Java 25 `mvn -B -ntp verify`, full GitHub Actions, independent code review.
4. Document production metrics and rollback before an explicitly approved deploy.
5. Leave #24 **open** until every remaining area is implemented and green. **No merge/deploy** for partial work.
