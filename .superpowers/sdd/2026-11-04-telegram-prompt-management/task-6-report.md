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
