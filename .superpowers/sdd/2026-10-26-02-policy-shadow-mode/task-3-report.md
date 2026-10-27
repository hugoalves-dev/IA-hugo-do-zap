# Task 3 — Decision engine, authorizations, templates, and kill switch

## Implemented

- Added `DecisionEngine.decide(Batch, Classification, EffectivePolicy, Instant)` with explicit status, reasons, blockers, template version references, simulated eligibility, and real-action eligibility. It has no delivery dependency.
- Added fail-closed flags (`ai_classification`, `intelligent_alerts`, `auto_reply` off; kill switch on), per-chat/per-situation authorization tied to an enabled, version-matched template, and required automation disclosure.
- Critical urgency requires explicit personal reference. A group trigger can match a group reply template but cannot supply a personal reference for critical urgency.
- Simulation and real cooldown windows are independent. Simulation cooldown applies at conversation scope and blocks simulated alerts and replies. The kill switch only gates real actions.
- Added V16 for settings, versioned templates, chat authorization, conversation cooldowns, and persisted decision state/real-action block details.
- Connected `SimulationService` to the engine and audit. No classifier exists in this plan, so the service records `classification_unavailable`; raw message presence does not become intent or authorization.

## RED → GREEN

- RED: `mvn '-Dtest=DecisionEngineTest' test` failed at test compilation because `DecisionEngine`, `Classification`, policy, and `Decision` APIs were absent.
- RED: `mvn '-Dtest=DecisionEngineTest#simulationCooldownBlocksAlertsAndRepliesTogether' test` failed because a recent simulation cooldown still allowed an alert.
- RED: state and unavailable-classifier tests failed to compile before those contract states existed.
- RED: PostgreSQL accepted an authorization bound to a template from a different situation.
- GREEN: `mvn '-Dtest=DecisionEngineTest,SimulationServiceTest,DatabaseMigrationTest,SpringFlywayStartupTest' test` passed: 22 tests, 0 failures, 0 errors, 0 skipped. PostgreSQL 17.11/Testcontainers covered migrations and simulation persistence.
- `git diff --check` passed before commit.

## Scope notes

- No IA provider, Telegram admin/menu, real WhatsApp delivery, full suite, plan 3, or VPS operation was added.
- CodeGraph returned no indexed symbols; targeted file reads and searches were used. RTK was unavailable in this shell.
- Existing `.idea/` and generated `target/` remain untouched and excluded from the task commit.

## Review follow-up

- RED: `automationTemplateMustDiscloseAutomatedReply` accepted body `Oi` when `identifiesAutomation=true`; PostgreSQL also accepted an enabled undisclosed template. A V16-to-V17 upgrade test showed the first V17 draft rewrote approved content.
- GREEN: reply eligibility now requires both the preapproval flag and a leading `Resposta automática:` declaration in the template body. V17 preserves legacy text and disables templates missing that prefix. A DB constraint prevents enabling those templates until their content contains the declaration.
- `decision_cooldowns.last_real_action_at` remains read-only in this plan because no real sender exists. Plan 3 MUST update it atomically with persisted confirmed-delivery status, only after a real send succeeds. It MUST NOT update it for simulation, an unconfirmed attempt, or a failed send. Plan 3 must test cooldown enforcement after confirmed success and no cooldown update after failure or ambiguous delivery.
- GREEN: `mvn '-Dtest=DecisionEngineTest,SimulationServiceTest,DatabaseMigrationTest,SpringFlywayStartupTest' test` passed: 25 tests, 0 failures, 0 errors, 0 skipped; PostgreSQL 17.11/Testcontainers covered the V16-to-V17 upgrade.
