# Task 2 — Debounce and simulation

## Implemented

- Added PostgreSQL-backed batches keyed by chat and participant. Group participants receive separate batches; private chats use an empty participant key.
- Added injectable enqueue timestamps and configurable inactivity/max-wait defaults of 10/30 seconds, message/batch capacity, and payload/audit retention.
- Added duplicate suppression, size-cap flushing, `SKIP LOCKED` claiming, 60-second claim recovery, and batch snapshots that survive process restart.
- Connected accepted, deduplicated `MessageService` events to the queue in the existing transaction. Group messages without a verified participant do not enter a shared simulation batch.
- Added a scheduled evaluator that only persists `wouldAlert`, `wouldReply`, `templateId`, `decisionReason`, and `blockedReason`. No WhatsApp or Telegram delivery is invoked.
- Scope evaluation admits unknown archive state only in `ALL`; restricted scopes persist `archive_state_unknown` or `archive_scope_excluded`. The evaluator reads the existing archive mode and `chat_snapshot`; no Evolution archived field is inferred.
- Added V15 tables, constraints, dedup index, sensitive payload expiry at up to 7 days, and audit metadata retention up to 365 days (90 by default). Retention removes payload rows before older audit metadata and keeps the batch while a later-expiring audit exists.

## RED → GREEN

- RED: `mvn '-Dtest=DebounceServiceTest,SimulationServiceTest' test` failed at test compilation because the task interfaces did not exist.
- GREEN: `mvn '-Dtest=DebounceServiceTest,SimulationServiceTest,MessageServiceTest,DatabaseMigrationTest,SpringFlywayStartupTest' test` passed: 26 tests, 0 failures, 0 errors, 0 skipped. PostgreSQL 17.11/Testcontainers ran the persistence and migration checks.
- Coverage includes 10-second idle debounce, 30-second cap, distinct group participants, duplicates, batch capacity, service recreation, `UNKNOWN` in all scope modes, restricted-scope blocks, failed audit persistence, legacy message integration, V15 migrations, Spring startup, and audit retention beyond sensitive payload expiry.
- `git diff --check` passed before staging.

## Notes and constraints

- CodeGraph returned zero indexed nodes and no symbol context; targeted `rg` and direct reads were used as fallback.
- RTK instructions were unavailable at `C:\Users\vitor.hugo\.codex-cli-private\RTK.md`.
- `.idea/` and `target/` are pre-existing untracked workspace content; excluded from this task commit.
- No full suite, VPS operation, push, or plan 3 work was performed.
