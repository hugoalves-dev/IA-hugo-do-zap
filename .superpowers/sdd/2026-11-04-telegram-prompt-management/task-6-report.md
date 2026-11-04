# Task 6: end-to-end regression, docs and verification

## Changes

- Added a PostgreSQL/Testcontainers acceptance case covering activation of a custom prompt, replacement by a second revision, reset to Java default (`revision=0` with the default hash), and restore of the first existing revision. It asserts exactly two custom revisions, no alert/reply/WhatsApp action rows, and unchanged real auto-reply configuration.
- Documented private-admin authorization, multipart editing bounds and expiry, preview/version confirmation, history, restore/reset, AI base choices, OpenRouter/budget limits, and prohibited input in README.
- Full verification exposed two Telegram polling regressions: expected `IllegalArgumentException`/invalid-date handling escaped the nested transaction, causing rejected audit/reply and polling-offset updates to roll back. Expected command failures are again handled inside the transaction callback; AI generation remains routed separately.
- Added an outer-transaction acceptance test for AI generation. The provider call runs under `PROPAGATION_NOT_SUPPORTED`, then the transaction resumes to save the proposal with draft-version CAS. The test asserts the fake provider sees no active transaction, the proposal is stored, and the active pointer/version remain unchanged.
- Updated repository test expectations for the corrected refinement accounting: the initial proposal is not one of the three refinements.

## Verification

JDK 25.0.5/Maven 3.9.11, host Testcontainers with Docker socket, `/tmp/issue45-settings.xml`, `/tmp/issue45-m2`, `/tmp/issue45-tools/cacerts`.

- Integrated activation/reset/restore test: 1 passed, 0 failures/errors/skips (`/tmp/issue45-task6-recheck.log`, included in the 64-test run below).
- Full focused task set after fixes: 115 tests passed, 0 failures/errors/skips. Command: `mvn -s /tmp/issue45-settings.xml -Dmaven.repo.local=/tmp/issue45-m2 -B -ntp -Dtest=OpenRouterPromptGeneratorTest,PromptGenerationServiceTest,TelegramPromptCommandServiceTest,AdminCommandServiceTest,ClassificationPromptLifecycleTest,AiSimulationAcceptanceTest test`. Log: `/tmp/issue45-task6-focused-final.log`.
- Polling rollback regression test: 2 passed, 0 failures/errors/skips. Log: `/tmp/issue45-task6-polling-green.log`.
- AI generation transaction suspension test: 1 passed, 0 failures/errors/skips. Log: `/tmp/issue45-task6-provider-tx.log`.
- Final `mvn -s /tmp/issue45-settings.xml -Dmaven.repo.local=/tmp/issue45-m2 -B -ntp verify`: **666 tests passed, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**. Log: `/tmp/issue45-task6-verify-final2.log`.
- `git diff --check` passed.

The first full run had 665 tests and exposed the two polling rollback regressions; those tests passed after the fix and the final full verification is green.

## Final review fix wave

- Restored OpenRouter's privacy and routing requirements on prompt-authoring requests: `zdr=true`, `data_collection=deny`, `allow_fallbacks=false`, and `require_parameters=true`; the request test asserts each field.
- Replaced synchronous Telegram authoring with V60 durable jobs. The command transaction writes bounded instructions, draft/version, actor/chat, and operation alongside the normal reply; a worker commits its claim before calling OpenRouter. A job is attempted once, ambiguous dispatched jobs become `UNKNOWN`, and terminal/unknown transitions erase instructions. User replies and audit entries contain no instructions or provider output.
- Moved the provider call to the post-claim worker path. The integration test verifies the fake transport sees no active transaction, a proposal saves without changing the active pointer, and terminal completion clears the queued instructions.
- Stale version-bound activate/reset/restore confirmations are checked before lifecycle methods can raise a conflict in the joined Telegram transaction. Outer-transaction PostgreSQL tests verify the callbacks are consumed, rejection replies and audit commit, and prompt state remains unchanged.
- Applied the three-refinement cap to both generation and refinement, before provider or budget calls; a repeated `/prompt_generate` test asserts zero requests and reservations at the cap.
- Updated the README to explain that AI authoring is queued and the single-attempt result arrives in the private chat.

Verification for this review fix wave:

- Focused prompt/Telegram/integration command: `/tmp/issue45-tools/apache-maven-3.9.11/bin/mvn -s /tmp/issue45-settings.xml -Dmaven.repo.local=/tmp/issue45-m2 -B -ntp -Dtest=OpenRouterPromptGeneratorTest,PromptGenerationServiceTest,AdminCommandServiceTest,AiSimulationAcceptanceTest,TelegramPromptUpdateHandlerTest,TelegramUpdatePollingServiceTest,TelegramPromptCommandServiceTest test` with JDK 25.0.5. **112 tests passed, 0 failures/errors/skips; BUILD SUCCESS.** Log: `/tmp/issue45-fix-focused-final5.log`.
- Migration focus: `/tmp/issue45-tools/apache-maven-3.9.11/bin/mvn -s /tmp/issue45-settings.xml -Dmaven.repo.local=/tmp/issue45-m2 -B -ntp -Dtest=SpringFlywayStartupTest,DatabaseMigrationTest test`. **13 tests passed, 0 failures/errors/skips; BUILD SUCCESS.** Log: `/tmp/issue45-fix-migration-focus.log`.
- Final full command: `/tmp/issue45-tools/apache-maven-3.9.11/bin/mvn -s /tmp/issue45-settings.xml -Dmaven.repo.local=/tmp/issue45-m2 -B -ntp verify` with JDK 25.0.5 and host Testcontainers. **667 tests passed, 0 failures/errors/skips; BUILD SUCCESS in 5:08.** Log: `/tmp/issue45-fix-verify-final.log`.
- One preceding full run found only the startup test's stale V59 assertion after adding V60. Updated `SpringFlywayStartupTest` to expect V60, confirmed the focused migration command above, and reran the full verifier successfully.
- `git diff --check` passed.
