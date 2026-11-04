# SDD ledger — plan: docs/superpowers/plans/2026-11-04-telegram-prompt-management.md

Pre-flight table:

| Tasks | Shared interface/files | Finding | Ruling |
|---|---|---|---|
| 1 → 2 | ClassificationPromptRepository records and lifecycle persistence operations | Task 2 consumes Task 1 repository records; schema/repository shapes agree. | None |
| 2 → 3 | ClassificationPromptService.DEFAULT_OPERATIONAL_PROMPT and ClassificationPromptSnapshot | Task 3 resolves one snapshot from Task 2; default revision 0 is consistent. | None |
| 2 → 4 | ClassificationPromptService lifecycle operations | Telegram adapter delegates draft, preview, activation, history, restore, and reset to Task 2. | None |
| 2 → 5 | Draft/proposal lifecycle and validation | Generator stores proposals through Task 2 and shares the same deterministic validator. | None |
| 4 → 5 | TelegramPromptCommandService | Task 5 extends the Task 4 adapter for proposal/refinement actions after the manual flow exists. | None |

Task self-consistency scan:

| Task | Files/tests/steps consistent? | Finding |
|---|---|---|
| 1 | Yes | V55 follows the checked-in migration ceiling V54; repository tests cover nullable reset history. |
| 2 | Yes | Validator and service tests precede implementation and consume Task 1 repository. |
| 3 | Yes | Provider snapshot interface is defined by Task 2; tests cover both provider request formats and Shadow audit. |
| 4 | Yes | Handler and command adapter consume Task 2 and existing callback tokens; tests cover private-chat restrictions. |
| 5 | Yes | Generator consumes Task 2 and extends the Task 4 adapter; provider and failure tests precede implementation. |
| 6 | Yes | Integration tests and README edits use Tasks 1–5 outputs; full Maven verification is specified. |

Task 1: pending
Task 2: pending
Task 3: pending
Task 4: pending
Task 5: pending
Task 6: pending
Task 1: Ruling: Draft metadata contract expanded to include description and optimistic version — AI proposals need durable descriptions and concurrent parts/refinements must not overwrite newer draft state — cost if wrong: proposal descriptions may be lost or simultaneous edits may be accepted out of order.
Task 1: minor (deferred): explicit revision IDs do not advance the PostgreSQL identity sequence; generated inserts could collide after an explicit ID. Normal lifecycle writes use generated IDs.
Task 1: focused Testcontainers run exposed a test datasource schema-selection defect; Flyway applied V55 successfully, but repository connections did not target the created schema. Root cause: test appends `?currentSchema=` even though the Testcontainers JDBC URL already contains `?loggerLevel=OFF`.
Task 1: fix round 1/5 (2 addressed, 0 open — JDBC schema selector and executed Testcontainers evidence; commits af318cf..84755d9)
Task 1: complete (commits b94b828..84755d9, review clean)
Task 2: in_progress
Task 2: Ruling: no placeholders are accepted initially because the current classifier sends conversation payload separately from the system prompt — this avoids interpolation of untrusted message text — cost if wrong: future prompt variables require a follow-up design instead of immediate templates.
Task 2: review round 1/5 (1 Important addressed: activation bound to the preview-confirmed draft version; 1 Minor addressed: null proposal origin rejected as invalid metadata; 1 Minor deferred: reordered lines may render as +0/-0 in the diff summary).
Task 2: complete (commits b729550..6003c04, 22 focused tests passed, independent review clean).
Branch update: complete (origin/main 2a22fb7 merged at 7b31eda; review fix f93fd88; PR #46 prompt state reused; V58 additive upgrade; 253 focused test executions across merge verification, review clean).
Branch update: Important finding addressed (legacy 4,000 character validation raised to 16,000; scoped re-review approved; commits 7b31eda..f93fd88).
Task 3: complete (commits 997f57c..2ad0806, 95 focused tests passed, review clean).
Task 4: in_progress (baseline 0a8c9cf; Task 3 has been pushed to PR #49).
Task 4: complete (commits 0a8c9cf..3582a97, 95 focused tests passed, review clean).
Task 5: in_progress (baseline b2437b0; Task 4 has been pushed to PR #49).
Task 5: fix round 1/5 (1 Important addressed, 0 open — failure-preservation tests for invalid output, provider errors, budget and configuration gates; commits 4601a3a..15cf592).
Task 5: complete (commits b2437b0..15cf592, 55 focused tests passed, review clean).
Task 5: minor (deferred): AI instruction text is supplied as `/prompt_generate` or `/prompt_refine` arguments rather than multiple plain-text messages; arguments are not placed in callbacks, audits, logs, or generic errors.
Task 6: in_progress (baseline 1e99ea0; Task 5 has been pushed to PR #49).
Task 6: implementer verification complete (115 focused tests; final `mvn verify` 666 tests, 0 failures/errors/skips; report `task-6-report.md`; final branch review pending).
Task 6: final review fix round (4 Important addressed: restore OpenRouter privacy/routing policy, durable generation jobs with committed claims and ambiguous outcome handling, reject stale confirmations without rolling back worker transaction, and enforce refinement cap before every provider call; commit f0073c1).
Task 6: complete (112 focused tests, 13 migration tests, full `mvn verify` 667 tests; scoped final re-review approved; `git diff --check` clean; branch pushed to PR #49).
Task 6: Ruling: generation requests use a durable one-attempt job queue, with interrupted dispatched jobs marked UNKNOWN — this avoids holding Telegram inbox locks during HTTP and avoids repeating a potentially paid request — cost if wrong: an interrupted generation may need explicit operator recovery rather than automatic retry.
Task 6: Ruling: keep stale callback rejection as a preflight check before lifecycle transaction execution — this prevents expected stale confirmations from poisoning the outer polling transaction — cost if wrong: a concurrent state change after preflight can still race, so lifecycle compare-and-set remains authoritative.
Task 6: Ruling: preserve OpenRouter zero-data-retention, deny-data-collection, no-fallback, and require-parameters options for authoring calls — this matches existing privacy and routing policy — cost if wrong: provider routes that cannot honor these options will reject the request.
Task 6: issue closure handoff is present in PR #49 (`Closes #45`); GitHub API verification was unavailable (Forbidden), and issue closure remains dependent on PR merge.
