# Task 4 report — reports and authorized actions

Date: 2026-10-27

## Implemented

- `ReportService.schedule(LocalDate)` aggregates simulation decision counts and reserved/reported cost by São Paulo calendar day. It stores no message text, preview, contact JID, or other raw conversation data.
- Daily report uses a date-keyed PostgreSQL transaction lock and the existing Telegram notification outbox. Repeated scheduling returns the same outbox action, including after the first action is acknowledged.
- `/relatorio` schedules the report; `TelegramMessageFormatter` renders the aggregate-only payload.
- WhatsApp actions now use the persisted `simulation_decisions.batch_id` as the provenance key. The service loads chat, participant, decision state, explicit template, permission flags, opt-in, and cooldown from PostgreSQL. It ignores caller-supplied decision data.
- Migration V23 adds a separate action queue, isolated from Telegram delivery. Enqueue requires the real-send flag, a persisted `WOULD_ACT` decision with `real_action_allowed`, an active and disclosed template, conversation/situation/template opt-in, action-specific enablement, kill switch off, and no cooldown or in-flight action for the conversation.
- Migration V24 limits action recipient/body retention to seven days. Purge deletes rows in every state, including `UNKNOWN`; persisted non-identifying decision tombstones prevent re-enqueue after purge.
- Dispatcher rechecks persisted decision, template, permissions, opt-in, cooldown and expiry before sending. Revoked authorization suppresses delivery. Timeout/ambiguous outcomes become `UNKNOWN` and cannot be retried.
- Confirmed delivery updates `last_real_action_at` and action state in one transaction. Simulation, failure, suppression, and timeout do not update cooldown. Provider reconciliation methods were removed: no authenticated Evolution receipt-to-action mapping exists yet.
- Real sending defaults OFF and the active Spring transport is disabled. No Evolution request or delivery confirmation is claimed. Task 5 must add an authenticated receipt contract and provenance mapping before real sends or reconciliation can be enabled.
- `intelligent_alert` now queues only an aggregate Telegram payload through the existing outbox. It requires persisted high/critical classification, confidence threshold, explicit opt-in, disclosed template, enabled flags, kill switch off, and current source evidence. A source message must structurally mention the configured owner JID or reply to an outbound message indexed from a validated `fromMe` webhook. Outbound reference records retain only chat JID, external ID and timestamps for at most seven days. Without `watcher.whatsapp-actions.owner-jid`, structured evidence, or live authorization, enqueue and dispatch block.
- Telegram remains at-least-once through the existing outbox. No claim of exactly-once delivery is made.

## RED / GREEN

- RED: `mvn -Dtest=ReportServiceTest,WhatsAppActionServiceTest test` failed compilation because the report/action services and result type were missing.
- PostgreSQL tests initially exposed invalid settled-cost fixture data and that legacy templates do not satisfy the required automation disclosure prefix. Fixtures were corrected to use a synthetic, explicitly disclosed template.
- RED: the new report-format test failed because the Telegram formatter had no daily summary branch. GREEN after adding that formatter path.
- RED: review follow-up tests initially exposed missing personal-reference validation, expiry constraint fixture error, and missing alert feature flags in a notification fixture.
- GREEN: from isolated scratch `H:\Script\IA-hugo-do-zap-evolution-poc-task4-scratch`, `mvn '-Dtest=WhatsAppActionServiceTest,IntelligentAlertServiceTest,NotificationServiceTest,TelegramMessageFormatterTest,SpringFlywayStartupTest,MessageServiceTest,RetentionRegressionTest' test` — 82 tests, 0 failures, 0 errors, 0 skipped; PostgreSQL 17 Testcontainers.
- GREEN follow-up: authenticated HTTP webhook → inbox/job → outbound `fromMe` reference → synthetic persisted reply decision queues an aggregate alert; unauthenticated `fromMe` request returns 401 and produces no inbox job or reference. Fixture proves this application's Bearer gate only; it does not prove Evolution's upstream `fromMe` authenticity.
- GREEN: isolated scratch `mvn '-Dtest=EvolutionWebhookControllerTest#onlyBearerAuthenticatedWebhookCanCreateOwnReplyReference' test` — 1 test, 0 failures/errors/skips; also asserts Spring injects `DisabledEvolutionActionTransport` by default.
- Tests cover bounded retention/purge, no resend after unknown/purge, no raw text or chat JID in alert payload, revocation revalidation, and feature defaults. No full suite was run.
- CodeGraph reported 0 indexed nodes. Targeted `rg` and direct reads traced simulation decisions, policy tables, Telegram outbox and formatter.

## Limits for task 5

- Classification and `BudgetService` remain disconnected from the simulation caller. Tests use synthetic persisted decision rows and fake transports. No AI mention evidence or sensitive text was fabricated.
- Do not enqueue real AI-generated actions until task 5 wires validated classification and budget reservations to each attempt, including retries/fallbacks.
- Evolution delivery remains `UNKNOWN` until Task 5 defines an authenticated provider receipt mapping. No public arbitrary-ID reconciliation API remains.
- Once the seven-day action TTL expires, an `UNKNOWN` action row is purged and cannot be reconciled later; its non-identifying decision tombstone still blocks replay. TTL purge does not cancel an HTTP request already in flight. The active Spring transport is unconditionally `DisabledEvolutionActionTransport`, and real-send defaults OFF, so this code cannot start such a request.
- Outbound `fromMe` trust currently relies on the shared-Bearer-protected webhook intake and validated inbox/job path. The test uses synthetic webhook JSON and does not establish that a live Evolution deployment authenticates or truthfully supplies `fromMe`; Task 5 must verify that upstream contract before relying on it.
- Alerts are implemented but will not enqueue from current production decision rows until Task 5 connects validated classification, budget and structured personal-reference evidence.
- No Telegram, WhatsApp, Evolution, AI provider, or VPS request was made. Tests use PostgreSQL Testcontainers and in-process fakes only.
