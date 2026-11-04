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
