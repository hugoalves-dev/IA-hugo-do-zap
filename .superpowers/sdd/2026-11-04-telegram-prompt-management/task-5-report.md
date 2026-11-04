# Task 5: bounded AI prompt proposals

## Implementation

- Added an OpenRouter-only prompt authoring client. It uses the configured model and timeout, safe endpoint validation, strict JSON response schema, a 2,048-token output cap and one existing conservative budget reservation per request. It does not log provider payloads, try Gemini, or return raw provider errors.
- Added generation/refinement orchestration over the canonical prompt draft. It requires configured AI, Shadow/classification availability and enabled local budget controls; rejects unsafe generated content through the deterministic validator; persists proposals as editable `ai_assisted` drafts; and never activates them. Provider calls are rejected if a SQL transaction is active.
- Updated refinement accounting so the initial AI proposal is not counted as a refinement; at most three subsequent refinements are accepted. Draft saves are bound to the version loaded before the provider call, preventing a concurrent manual edit from being overwritten.
- Connected private `/prompt_generate` and `/prompt_refine` commands to the Task 4 active or Java-default draft. Instruction text is accepted as explicit private command text, is not stored in callbacks/audit records/logs, and is not returned in errors. Result replies contain only a bounded change summary. Both base choices are available in the prompt menu.

## Verification

Used JDK 25.0.5, Maven 3.9.11, `/tmp/issue45-settings.xml`, `/tmp/issue45-m2`, and `/tmp/issue45-tools/cacerts`; Testcontainers ran on the host using the authorized Docker socket.

- Initial compile/tests exposed and corrected test fixture stubbing issues and refinement counting behavior.
- Final command: `mvn -s /tmp/issue45-settings.xml -Dmaven.repo.local=/tmp/issue45-m2 -B -ntp -Dtest=OpenRouterPromptGeneratorTest,PromptGenerationServiceTest,TelegramPromptCommandServiceTest,AdminCommandServiceTest,ClassificationPromptLifecycleTest test`
- Result: **52 tests passed, 0 failures, 0 errors, 0 skipped**. Log: `/tmp/issue45-task5-final.log`.
- `git diff --check` passed.

## Limitations

- Telegram instructions are supplied as arguments to `/prompt_generate` and `/prompt_refine`, rather than accumulated as multiple plain-text instruction messages. They are sent only to the configured OpenRouter model over the bounded generation request.
- Full `mvn verify` remains the Task 6 gate.

## Review fix round 1: failure preservation coverage

Added service-level regression coverage for unsafe generated output, provider timeout, HTTP 429, HTTP 503, local budget rejection, missing OpenRouter credentials, and disabled Shadow. Each case asserts the pre-existing draft and active revision/state version are unchanged, no revision save or state transition is attempted, and provider calls are skipped for local gate failures.

Exact final command: `mvn -s /tmp/issue45-settings.xml -Dmaven.repo.local=/tmp/issue45-m2 -B -ntp -Dtest=OpenRouterPromptGeneratorTest,PromptGenerationServiceTest,TelegramPromptCommandServiceTest,AdminCommandServiceTest,ClassificationPromptLifecycleTest test`.

Result: **55 tests passed, 0 failures, 0 errors, 0 skipped**. Log: `/tmp/issue45-task5-review-fix.log`.
