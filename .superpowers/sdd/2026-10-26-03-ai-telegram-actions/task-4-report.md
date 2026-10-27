# Task 4 report — reports and authorized actions

Date: 2026-10-27

## Implemented

- `ReportService.schedule(LocalDate)` aggregates simulation decision counts and reserved/reported cost by São Paulo calendar day. It stores no message text, preview, contact JID, or other raw conversation data.
- Daily report uses a date-keyed PostgreSQL transaction lock and the existing Telegram notification outbox. Repeated scheduling returns the same outbox action, including after the first action is acknowledged.
- `/relatorio` schedules the report; `TelegramMessageFormatter` renders the aggregate-only payload.
- WhatsApp actions now use the persisted `simulation_decisions.batch_id` as the provenance key. The service loads chat, participant, decision state, explicit template, permission flags, opt-in, and cooldown from PostgreSQL. It ignores caller-supplied decision data.
- Migration V23 adds a separate action queue, isolated from Telegram delivery. Enqueue requires the real-send flag, a persisted `WOULD_ACT` decision with `real_action_allowed`, an active and disclosed template, conversation/situation/template opt-in, action-specific enablement, kill switch off, and no cooldown or in-flight action for the conversation.
- Dispatcher marks an action `DISPATCHED` before the request and rechecks persisted decision, template, permissions, opt-in, and cooldown immediately before sending. Revoked authorization suppresses delivery.
- Confirmed delivery updates `last_real_action_at` and action state in one transaction. Definite failure, simulation, suppression, and timeout do not update cooldown. `DISPATCHED`/`UNCERTAIN` are never automatically retried; explicit delivery/non-delivery evidence is required for reconciliation.
- Real sending defaults OFF. The default transport never contacts Evolution. An explicit opt-in config enables the HTTPS Evolution `sendText` client; HTTP success means Evolution accepted the request, not proof of WhatsApp delivery, so it remains `UNCERTAIN` until a delivery receipt is reconciled.
- Telegram remains at-least-once through the existing outbox. No claim of exactly-once delivery is made.

## RED / GREEN

- RED: `mvn -Dtest=ReportServiceTest,WhatsAppActionServiceTest test` failed compilation because the report/action services and result type were missing.
- PostgreSQL tests initially exposed invalid settled-cost fixture data and that legacy templates do not satisfy the required automation disclosure prefix. Fixtures were corrected to use a synthetic, explicitly disclosed template.
- RED: the new report-format test failed because the Telegram formatter had no daily summary branch. GREEN after adding that formatter path.
- GREEN: from isolated scratch `H:\Script\IA-hugo-do-zap-evolution-poc-task4-scratch`, `mvn -Dtest=ReportServiceTest,WhatsAppActionServiceTest,TelegramMessageFormatterTest,SpringFlywayStartupTest,AdminCommandServiceTest test` — 19 tests, 0 failures, 0 errors, 0 skipped; PostgreSQL 17 Testcontainers.
- Tests cover raw-text/JID absence and date idempotency, persisted scope/Shadow Mode block, opt-in and kill switch, duplicate enqueue, revocation before send, timeout without retry, cooldown only after confirmed delivery, feature default OFF, formatter output, startup migration V23, and existing admin command behavior.
- CodeGraph reported 0 indexed nodes. Targeted `rg` and direct reads traced simulation decisions, policy tables, Telegram outbox and formatter.

## Limits for task 5

- Classification and `BudgetService` remain disconnected from the simulation caller. Current action tests use synthetic persisted decision rows and a fake transport. This task did not create or infer any AI mention evidence or sensitive text.
- Do not enqueue real AI-generated actions until task 5 wires validated classification and budget reservations to each attempt, including retries/fallbacks.
- The HTTP client treats all Evolution 2xx results as ambiguous acceptance, not delivery confirmation. Task 5 must connect an authenticated Evolution delivery receipt to `reconcileDelivered`; otherwise `last_real_action_at` correctly remains unchanged.
- `intelligent_alert` Telegram notification was not generated because no real classification evidence is connected. Daily summary is available without fabricating content.
- No Telegram, WhatsApp, Evolution, AI provider, or VPS request was made. Tests use PostgreSQL Testcontainers and in-process fakes only.
