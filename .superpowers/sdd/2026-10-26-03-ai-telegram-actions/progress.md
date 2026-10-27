# SDD ledger — plan: docs/superpowers/plans/2026-10-26-03-ai-telegram-actions.md
Pre-flight: Task 1 ClassificationService needs Task 2 BudgetService before paid calls; Tasks 1/2 feed Task 4; Task 3 admins feed policy/settings and Task 4 reporting; Task 5 validates all.
Ruling: Until Task 2 budget reservation is implemented and wired, Task 1 must block paid requests and avoid enabling production AI. Cost: classification unavailable until budget integration.
Ruling: Flyway V3 already exists; use next available migration. Cost: filenames differ from plan.
Ruling: Portuguese task heading requires manual brief extraction; cost: extraction must be checked.
User approved Plan 3; push after each reviewed task. Full test suite explicitly disallowed; run focused tests.
Task 1: complete (57dcc0a..b50009a); 25 focused tests pass in scratch, review clean, pushed. Paid calls remain disconnected until Task 2 budget.
Task 2: Ruling: use V20 because repository migrations already reach V19; retain ambiguous reservations at the full estimate because timeout/failure does not prove zero provider cost. Keep classification and simulation disconnected because no real data authorization or per-attempt settlement path is configured.
Task 2: complete; `mvn -Dtest=BudgetServiceTest,AiRetentionTest test` in isolated scratch passed with PostgreSQL 17: 6 tests, 0 failures/errors/skips. Report: `.superpowers/sdd/2026-10-26-03-ai-telegram-actions/task-2-report.md`.
