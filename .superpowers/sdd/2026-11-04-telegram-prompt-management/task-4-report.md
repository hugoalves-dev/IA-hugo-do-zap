# Task 4: private Telegram manual prompt management

## Implementation

- V59 adds a nullable draft reference to existing private input sessions; V58 sessions survive the upgrade and retain their legacy one-message behavior. New `/prompt_new` sessions create an editable durable draft and append authorized private text parts to that draft. `/prompt_clear` permits replacing the operational prompt in parts; `/prompt_cancel` closes the session and draft. Expiration, owner and chat binding are enforced on session and draft reads.
- `/prompt_preview` validates and displays the full bounded draft and change summary, splitting output into chunks of at most 3,000 Java characters. A valid preview issues a short opaque navigation token; the next step issues a separate single-use activation confirmation. The token contains the draft ID/version, base revision and active state version, never prompt text.
- History, restore and reset use the existing canonical prompt state and outbox. Restore/reset display current and target text and require confirmation. A monotonically increasing state version is checked under a database row lock alongside the expected active revision so confirmations are rejected after an A→B→A state change. Existing lifecycle method overloads remain available.
- The prompt menu exposes manual editing, preview, history, restore through history, reset and an AI generation extension seam for Task 5. Existing `/prompt_activate`, `/prompt_restore` with the legacy two-argument form, prompt paging, comparison and old session APIs remain available.

## Verification

JDK 25.0.5 and Maven 3.9.11 ran on the host with the authorized Docker socket and PostgreSQL 17 Testcontainers, using `/tmp/issue45-settings.xml`, `/tmp/issue45-m2` and `/tmp/issue45-tools/cacerts`.

- RED: `mvn -s /tmp/issue45-settings.xml -Dmaven.repo.local=/tmp/issue45-m2 -B -ntp '-Dtest=AdminCommandServiceTest#promptRevisionFlowCollectsPartsAndConfirmsThePreviewedVersion' test` failed because the old one-message session was consumed after the first part (`/tmp/issue45-task4-red.log`).
- GREEN: the same focused test passed after session binding (`/tmp/issue45-task4-green-one.log`).
- Final focused suite: `mvn -s /tmp/issue45-settings.xml -Dmaven.repo.local=/tmp/issue45-m2 -B -ntp -Dtest=TelegramPromptUpdateHandlerTest,TelegramPromptCommandServiceTest,TelegramUpdateHandlerTest,AdminCommandServiceTest,CallbackTokenServiceTest,TelegramWebhookControllerTest,ClassificationPromptLifecycleTest,ClassificationPromptRepositoryTest,ClassificationPromptServiceTest,JdbcClassificationPromptRepositoryTest,DatabaseMigrationTest,SpringFlywayStartupTest test` — **95 tests, 0 failures, 0 errors, 0 skipped** (`/tmp/issue45-task4-final.log`). This covers private identity/chat authorization, replay, stale draft and state confirmations including A→B→A, chunking, invalid drafts, clear/cancel/expiry/re-entry, legacy session compatibility, reset/restore history and a populated V58→V59 upgrade.
- `git diff --check` passed.

The AI menu entry is intentionally a disabled extension seam until Task 5 implements provider-backed authoring. Full `mvn verify` remains the Task 6 integration gate.
