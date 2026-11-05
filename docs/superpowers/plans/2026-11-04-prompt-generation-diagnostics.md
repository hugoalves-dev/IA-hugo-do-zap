# Prompt generation diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make prompt generation failures identifiable per job and keep a payload-free record of whether its Telegram result was delivered, without repeating uncertain paid provider requests.

**Architecture:** Add typed stage/reason failures and propagate a job correlation context through the existing single-attempt provider path. Persist generation diagnostics and a minimal result-delivery summary on the durable job row, mirroring existing outbox transitions inside the same database transactions. Keep the current outbox, RabbitMQ, and Telegram retry path.

**Tech Stack:** Java 25, Spring Boot 4, Spring JDBC, Flyway, Micrometer, PostgreSQL 17, Testcontainers, Maven.

**Spec:** `docs/superpowers/specs/2026-11-04-prompt-generation-diagnostics-design.md`

## Global Constraints

- Terminal `FAILED` or `UNKNOWN` jobs carry a sanitized `failure_stage` and `failure_code`; successful jobs carry neither. A `DISPATCHED` job may temporarily carry only `OUTBOX/RESULT_ENQUEUE_FAILED` so stale recovery can preserve that cause.
- Stages are finite: `CLAIM`, `PREFLIGHT`, `PROVIDER`, `VALIDATE`, `PERSIST`, `OUTBOX`, `DELIVERY`.
- Provider `DISPATCHED` is a budget ledger transition before the HTTP delegate; log transport invocation and received HTTP response as distinct facts.
- Never repeat a paid call after provider dispatch or an uncertain result. Keep proposals editable until the existing explicit activation flow.
- Keep job terminal update and result outbox admission atomic. If it fails, stale-job recovery may emit an `UNKNOWN` notice but must not call the provider again.
- Mirror only safe delivery metadata on the job row. Do not extend the outbox payload's current retention.
- Prometheus labels may contain only finite operation, status, stage, and reason values. UUIDs remain log fields, not metric labels.
- Do not change production flags, call real providers, send real Telegram/WhatsApp messages, or deploy.

## Review Focus

- A provider timeout after reservation dispatch must remain `UNKNOWN`/uncertain and must not cause a second provider invocation. Add a test to Task 1's `BudgetedProviderTransportTest`.
- An existing or concurrently claimed `prompt-generation-result` action must preserve current idempotency/lease ownership and keep its payload out of logs. Add a test to Task 3's `PromptGenerationResultDeliveryRepositoryTest`.
- A Rabbit publisher confirm failure after the database commit must leave the outbox action recoverable without reverting the completed job or rerunning generation. Add a test to Task 3's `NotificationServiceTest`.
- Expiry racing with an acknowledged Telegram action must retain `ACKNOWLEDGED` in the job summary while expiring/removing the outbox payload. Add a test to Task 3's `PromptGenerationResultDeliveryRepositoryTest`.
- Malformed or secret-bearing provider errors must yield only a stable safe code in the job, logs, and Telegram reply. Add a test to Task 1's `PromptGenerationServiceTest`.

---

### Task 1: Typed generation failures and provider-attempt evidence

**Files:**
- Create: `src/main/java/dev/hugo/watcher/ai/PromptGenerationFailure.java`
- Create: `src/main/java/dev/hugo/watcher/ai/PromptGenerationContext.java`
- Modify: `src/main/java/dev/hugo/watcher/ai/PromptGenerationService.java`
- Modify: `src/main/java/dev/hugo/watcher/ai/ClassificationPromptRepository.java`
- Modify: `src/main/java/dev/hugo/watcher/ai/JdbcClassificationPromptRepository.java`
- Modify: `src/main/java/dev/hugo/watcher/ai/OpenRouterPromptGenerator.java`
- Modify: `src/main/java/dev/hugo/watcher/ai/BudgetedProviderTransport.java`
- Modify: `src/test/java/dev/hugo/watcher/ai/PromptGenerationServiceTest.java`
- Modify: `src/test/java/dev/hugo/watcher/ai/JdbcClassificationPromptRepositoryTest.java`
- Modify: `src/test/java/dev/hugo/watcher/ai/BudgetedProviderTransportTest.java`

**Interfaces:**
- `PromptGenerationFailure` carries only `Stage` and `ReasonCode`; its message is a stable code and it never serializes a cause or provider response.
- `PromptGenerationContext` carries `jobId` and the finite operation (`GENERATE` or `REFINE`) for logs only.
- Add `ClassificationPromptRepository.draftForGeneration(UUID id, long ownerId)` and implement it with an owner-scoped query that may return an expired draft; keep the existing expiry-filtered `draft(...)` contract unchanged for other callers.
- Add context-aware overloads to `PromptGenerationService.propose/refine` and `OpenRouterPromptGenerator.generate`; keep existing overloads delegating with no context for current callers/tests.
- `BudgetedProviderTransport` accepts optional prompt-generation context and emits only `TRANSPORT_INVOKED`, `HTTP_RESPONSE_RECEIVED` (status code), or `TRANSPORT_FAILURE` facts. It preserves reservation transitions and never logs request/response bodies or Throwable messages.

- [ ] **Step 1: Write the failing tests**
  - `PromptGenerationServiceTest.disabledLocalBudgetHasStablePreflightFailure`
  - `PromptGenerationServiceTest.expiredAndChangedDraftHaveDistinctFailureCodes`
  - `PromptGenerationServiceTest.providerFailureDoesNotExposeProviderMessage`
  - `BudgetedProviderTransportTest.promptContextSeparatesTransportInvocationFromUncertainReservation`
  - `JdbcClassificationPromptRepositoryTest.generationLookupReturnsOwnedExpiredDraftOnly`
  - Assert that `WATCHER_AI_DISABLE_LOCAL_BUDGET=true` is `PREFLIGHT/BUDGET_MODE_BLOCKED`, draft expiry and version conflict have distinct codes, no secret-bearing cause/message appears in exposed diagnostics, and an uncertain transport is never retried.
- [ ] **Step 2: Run the focused tests and confirm they fail for the missing typed diagnostics/context.**
- [ ] **Step 3: Add the finite failure/context types and propagate them through service, generator, and the budgeted transport.** Map only known safe exception categories; retain the existing fail-closed preflight, reservation, and one-attempt behavior.
- [ ] **Step 4: Run `mvn -B -ntp -Dtest=PromptGenerationServiceTest,JdbcClassificationPromptRepositoryTest,BudgetedProviderTransportTest test` and confirm the new tests pass.**
- [ ] **Step 5: Commit the task as `feat(ai): classify prompt generation failures`.**
- [ ] **Step 6: Record the task completion and command result in the execution ledger.**

### Task 2: Persist per-job failure reason and lifecycle metrics

**Files:**
- Create: `src/main/java/dev/hugo/watcher/ai/PromptGenerationMetrics.java`
- Create: `src/main/resources/db/migration/V61__prompt_generation_diagnostics.sql`
- Modify: `src/main/java/dev/hugo/watcher/ai/PromptGenerationJob.java`
- Modify: `src/main/java/dev/hugo/watcher/ai/PromptGenerationQueueRepository.java`
- Modify: `src/main/java/dev/hugo/watcher/ai/PromptGenerationWorker.java`
- Modify: `src/main/java/dev/hugo/watcher/telegram/AdminCommandService.java`
- Modify: `src/test/java/dev/hugo/watcher/DatabaseMigrationTest.java`
- Modify: `src/test/java/dev/hugo/watcher/ai/AiSimulationAcceptanceTest.java`
- Create: `src/test/java/dev/hugo/watcher/ai/PromptGenerationMetricsTest.java`
- Modify: `src/test/java/dev/hugo/watcher/telegram/AdminCommandServiceTest.java`
- Modify: `src/test/java/dev/hugo/watcher/ai/PromptGenerationServiceTest.java`

**Interfaces:**
- `V61` adds nullable finite `failure_stage`/`failure_code`, and payload-free delivery summary fields `result_delivery_status`, `result_delivery_attempts`, `result_delivery_last_error`, `result_delivery_rabbit_confirmed_at`, `result_delivery_expires_at`; default delivery status is `NOT_ENQUEUED` and attempts are zero.
- The migration constrains stage, failure code, and delivery status to the finite values used by code. The job mapper returns no instructions in diagnostic projections.
- Extend `PromptGenerationJob` with the existing fields needed for timing/correlation, without exposing instructions to logs.
- `PromptGenerationQueueRepository.finish(...)` accepts terminal state plus optional sanitized failure stage/code; `finishUnknown(...)` writes `UNKNOWN/WORKER_INTERRUPTED` unless a more specific safe result-enqueue failure is already persisted.
- If result outbox admission fails, `PromptGenerationWorker` logs `OUTBOX/RESULT_ENQUEUE_FAILED` and asks `PromptGenerationQueueRepository.recordResultEnqueueFailure(...)` to persist that safe code independently while the job remains `DISPATCHED`; stale recovery carries it into `UNKNOWN`.
- `PromptGenerationMetrics` exposes fixed-name counters and timers with allowlisted tags only; no job, chat, draft, or reservation identifier is a tag.

- [ ] **Step 1: Add failing migration/acceptance tests**
  - `AiSimulationAcceptanceTest.failedPromptGenerationPersistsSafeReasonAndQueuesAdminReply`
  - `AiSimulationAcceptanceTest.outboxAdmissionFailureRecoversAsUnknownWithoutProviderReplay`
  - `AiSimulationAcceptanceTest.unknownPromptJobStaysTerminalAcrossWorkerRestart`
  - `AdminCommandServiceTest.promptGenerationEnqueueRecordsOnlyBoundedOperationMetric`
  - `DatabaseMigrationTest.upgradesPromptGenerationDiagnosticsFromV60`
  - `PromptGenerationMetricsTest.jobMetricsUseOnlyBoundedTags`
  - Assert terminal code/stage persistence, transaction rollback on outbox insert failure, later safe `UNKNOWN` notice, zero second provider calls, and no instructions in diagnostic columns/metrics.
- [ ] **Step 2: Run the focused tests and confirm they fail because migration/diagnostic persistence/metrics are absent.**
- [ ] **Step 3: Add V61, update the job mapper/terminal SQL, record enqueue/claim/terminal metrics, and replace the silent `RuntimeException` catch with stage/code logging and safe user replies.** Log job UUID only as a structured field; do not attach unsafe Throwable messages.
- [ ] **Step 4: Run `mvn -B -ntp -Dtest=AiSimulationAcceptanceTest,AdminCommandServiceTest,DatabaseMigrationTest,PromptGenerationMetricsTest test` and confirm the new tests pass.**
- [ ] **Step 5: Commit the task as `feat(ai): persist prompt generation diagnostics`.**
- [ ] **Step 6: Record the task completion and command result in the execution ledger.**

### Task 3: Preserve result-delivery evidence through outbox retention

**Files:**
- Create: `src/main/java/dev/hugo/watcher/ai/PromptGenerationResultDeliveryRepository.java`
- Modify: `src/main/java/dev/hugo/watcher/NotificationOutboxRepository.java`
- Modify: `src/main/java/dev/hugo/watcher/RetentionRepository.java`
- Modify: `src/main/java/dev/hugo/watcher/NotificationRabbitConsumer.java`
- Modify: `src/main/java/dev/hugo/watcher/PersistenceBeansConfiguration.java`
- Create: `src/test/java/dev/hugo/watcher/ai/PromptGenerationResultDeliveryRepositoryTest.java`
- Modify: `src/test/java/dev/hugo/watcher/NotificationServiceTest.java`
- Modify: `src/test/java/dev/hugo/watcher/NotificationRabbitConsumerTest.java`

**Interfaces:**
- `PromptGenerationResultDeliveryRepository` updates one job by parsing only the canonical `prompt-generation-result:<UUID>` key and mirrors enqueue, publish confirmation, claim, acknowledgement, retry, and expiry. Unknown/malformed action keys are ignored; other outbox action types are untouched.
- Delivery status values mirror outbox states. On expiry, preserve `ACKNOWLEDGED`; mark only an unacknowledged action `EXPIRED`. Store only bounded sanitized `last_error`, attempts, confirmation, and expiry metadata.
- Record a counter `watcher.prompt.generation.delivery` tagged with finite delivery status; do not tag it with action key or job UUID.
- `NotificationRabbitConsumer.isAdminAction` treats `prompt-generation-result:` as an admin path for existing low-cardinality delivery metrics.

- [ ] **Step 1: Write failing transition tests**
  - `PromptGenerationResultDeliveryRepositoryTest.mirrorsOutboxLifecycleWithoutPayload`
  - `PromptGenerationResultDeliveryRepositoryTest.retentionPreservesAcknowledgedStatusAndExpiresUnacknowledgedAction`
  - `NotificationServiceTest.rabbitConfirmFailureLeavesCompletedResultActionReplayable`
  - `NotificationRabbitConsumerTest.promptGenerationResultIsMeasuredAsAdminDelivery`
  - Assert delivery metadata persists after outbox row deletion, Rabbit confirmation and Telegram acknowledgement are distinct, retries only affect transport, and WhatsApp actions are unchanged.
- [ ] **Step 2: Run the focused tests and confirm they fail because no durable delivery summary is updated/classified.**
- [ ] **Step 3: Add the delivery repository and call it transactionally from outbox and retention transitions; classify the job result action as administrative in Rabbit metrics.**
- [ ] **Step 4: Run `mvn -B -ntp -Dtest=PromptGenerationResultDeliveryRepositoryTest,NotificationServiceTest,NotificationRabbitConsumerTest test` and confirm the new tests pass.**
- [ ] **Step 5: Commit the task as `feat(telegram): trace prompt result delivery`.**
- [ ] **Step 6: Record the task completion and command result in the execution ledger.**

### Task 4: Document operator-visible diagnostics and complete verification

**Files:**
- Modify: `README.md`
- Verify: `src/main/java/dev/hugo/watcher/ai/`, `src/main/java/dev/hugo/watcher/NotificationOutboxRepository.java`, `src/main/java/dev/hugo/watcher/NotificationRabbitConsumer.java`

- [ ] **Step 1: Document the stable failure/metric names, safe admin guidance for local-budget and draft failures, delivery-summary query fields, and the distinction between reservation dispatch and HTTP response.** Do not document production credentials or invite real paid reproduction.
- [ ] **Step 2: Run focused provider, worker, notification, and Telegram tests, then run the release gates:**
  - `python3 -B -m unittest discover -s tests/ci -p 'test*.py'`
  - `mvn -B -ntp verify`
- [ ] **Step 3: Review the full diff for sensitive-data exposure, no real-action gate changes, no unsafe provider retry, and `git diff --check`.**
- [ ] **Step 4: Commit the documentation as `docs: document prompt generation diagnostics`.**
- [ ] **Step 5: Record the task completion and command result in the execution ledger.**

## Final review focus

Review the diff and tests against the five cases in `Review Focus`, especially job/outbox atomicity, summary updates on every result-action state transition, and the guarantee that neither a Rabbit retry nor `UNKNOWN` recovery repeats provider generation.
