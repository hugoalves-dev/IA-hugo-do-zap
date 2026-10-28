# Task 1 follow-up: independent runtime modes

## Root cause

With `watcher.simulation.enabled=false`, `MessageService` omitted the durable capture, `DebounceService` refused to drain or claim batches, and `SimulationScheduler` was absent. When present, the scheduler also returned early for `telegram_admin_settings.simulation_enabled=false`, blocking real mode.

## Changes

- Capture and batch processing now run when either shadow simulation or `watcher.decision-producer.real-mode-enabled` is enabled.
- The scheduler remains active when real mode is enabled. The database admin simulation switch gates only shadow-only work; real mode continues through the existing consent, policy, kill-switch, cooldown, and outbox gates.
- With both modes off, message capture is omitted and `DebounceService` returns no batches.
- Updated README mode precedence. Added Postgres/fake-provider coverage for real-only and both-off behavior, plus scheduler/admin override coverage.
- Reset `pending_notification`, `processed_message`, and `telegram_admin_settings.simulation_enabled` in the AI acceptance fixture to prevent cross-test state leakage.

## Verification

- TDD RED: real-only end-to-end test produced 0 decisions where 1 was expected. Scheduler property test confirmed no scheduler bean when shadow was off and real mode was on.
- Focused GREEN: `mvn '-Dtest=MessageServiceTest,DebounceServiceTest,SimulationSchedulerTest,AiSimulationAcceptanceTest' test` passed (81 tests, 0 failures/errors/skips).
- `git diff --check` passed.
- PostgreSQL ran in local Testcontainers. Classification used a fake provider. No Evolution or Telegram endpoint, credentials, pairing, or real send was used; tests verify outbox queueing only.

The first combined run exposed fixture leakage from the new message-ingress cases: their pending notifications affected a later Telegram assertion. The acceptance fixture now clears those rows and restores its admin switch before every test; the combined rerun passed.
