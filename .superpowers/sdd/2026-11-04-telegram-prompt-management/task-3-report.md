# Task 3 report: Shadow prompt identity

## Implementation

The merged PR #46 code already composes the operational prompt and immutable core, sends one selected snapshot to both providers, and stores revision/hash in the AI evaluation JSON. The new acceptance coverage changes the active revision while the primary request is in progress, then verifies the fallback received the same system text, both response schemas match the fixed Java schema, and the stored evaluation keeps the first revision/hash. It reuses the batch after the change and confirms the original metadata is returned without another provider call or any WhatsApp/alert outbox action.

The acceptance test exposed a defect: the freshly returned `Classification` omitted prompt revision/hash even though the persisted evaluation included them. `AiSimulationClassifier` now attaches the same snapshot identity to the return value after a successful save. Cached evaluations continue to decode their original identity.

The added resolution-failure test verifies that a `prompt_hash_mismatch` from the prompt service returns `classification_prompt_unavailable` before any provider call. `ClassificationPromptServiceTest.legacyRevisionWithIncorrectHashFailsClosed` already exercises detection of an actual incorrect stored hash.

## Verification

JDK 25.0.5 and Maven 3.9.11 were run on the host with the authorized Docker socket and PostgreSQL 17 Testcontainers. Maven used `/tmp/issue45-settings.xml`, `/tmp/issue45-m2`, and the `/tmp/issue45-tools/cacerts` truststore.

- RED: `mvn -s /tmp/issue45-settings.xml -Dmaven.repo.local=/tmp/issue45-m2 -B -ntp '-Dtest=AiSimulationAcceptanceTest#onePromptSnapshotIsUsedForFallbackAndRetainedWithTheStoredEvaluation+promptResolutionFailureBlocksProviderCalls' test` — 2 tests, 1 failure: expected revision 1 but received 0 from the first returned classification. Full log: `/tmp/issue45-task3-red.log`.
- GREEN: the same command after the fix — 2 tests, 0 failures/errors/skips. Full log: `/tmp/issue45-task3-green.log`.
- Focused suite: `mvn -s /tmp/issue45-settings.xml -Dmaven.repo.local=/tmp/issue45-m2 -B -ntp -Dtest=OpenRouterProviderTest,GeminiProviderTest,AiSimulationAcceptanceTest,ClassificationServiceTest test` — 95 tests, 0 failures/errors/skips. Full log: `/tmp/issue45-task3-focused.log`.
- `git diff --check` passed.

Full `mvn verify` remains the final integration gate from Task 6.
