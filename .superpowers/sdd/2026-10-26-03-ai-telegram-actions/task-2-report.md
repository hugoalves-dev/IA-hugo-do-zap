# Task 2 report — budget and AI retention

Date: 2026-10-27

## Scope

- Added V20 PostgreSQL budget limits and reservation ledger. No default limit row exists; missing daily or monthly limit blocks reservation before paid work.
- Reservations lock the singleton limits row inside a PostgreSQL transaction. Concurrent calls cannot reserve beyond either configured UTC period limit.
- Estimated and actual costs use integer USD micros. Settlement adjusts reserved spend to informed actual cost, even if actual exceeds estimate. `RESERVED` and `UNCERTAIN` costs count against both windows.
- Ambiguous provider failure/timeout remains counted until reconciled. Repeated settle/release calls are idempotent; release does not erase a settled charge.
- Metrics contain operation outcomes only. The ledger contains amounts and lifecycle metadata, no content, provider, or user identifiers.
- Existing `RetentionService` deletes simulation message snippets after their bounded expiry while decision metadata follows its independent deadline. `AiRetentionTest` verifies that separation.
- Classification and simulation remain disconnected from paid providers. There is no real data-authorization decision, configured budget limits, or settlement path for every HTTP retry/fallback. The `ai_classification_enabled` schema default remains false. No provider credentials or live network were used.
- Cost meanings and activation prerequisites are documented in `docs/ai-budget.md`.

## RED / GREEN

- RED: `mvn -Dtest=BudgetServiceTest,AiRetentionTest test` failed compilation because `BudgetService`, its cost/reservation types, and the tests' budget exception were not yet present.
- GREEN: the same focused command passed in the dedicated scratch copy with PostgreSQL 17 Testcontainers: 6 tests, 0 failures, 0 errors, 0 skipped.
- Coverage: absent daily/monthly limits block; concurrent reservations cannot exceed configured limits; a monthly cap blocks with daily headroom; ambiguous timeout holds the estimate; reconciliation and duplicate settlement/release preserve accounting; sensitive simulation snippets expire while decision metadata remains.
- The first post-implementation Maven invocation was mistakenly run from the worktree and may have refreshed its pre-existing untracked `target/`. No files from `.idea/` or `target/` were read, staged, or committed. All subsequent verification ran in the dedicated scratch copy.
- CodeGraph and CodeGraph memory search returned an unindexed workspace (0 nodes/results); targeted `rg` and direct source inspection supplied the fallback.

## Limits

- A conservative estimate and provider-reported actual cost require a caller-side pricing policy. Provider account quotas and actual billing are external and cannot be guaranteed by this ledger.
- No production caller is enabled because data authorization and per-attempt budget settlement are not available yet.
