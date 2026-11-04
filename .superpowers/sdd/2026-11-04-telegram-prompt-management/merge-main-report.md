# Merge origin/main integration report

The feature branch was based on f3fcd30 and main advanced to 2a22fb7 via PR #46. The merge reconciles the overlapping prompt implementation with one `ClassificationPromptService`, one `ClassificationPromptRepository` interface implemented by `JdbcClassificationPromptRepository`, and the pre-existing `classification_prompt_state` active pointer and versioned history. Existing #46 API methods, Telegram input sessions, and Shadow provider composition remain available; richer draft and revision operations share the same immutable revisions and active pointer. The `ClassificationPromptSnapshot` retains existing `sha256()` / `systemText()` accessors and exposes content plus issue #45 metadata; its revision remains the existing integer identifier.

V58 adds bounded persistent drafts, origin/description, nullable reset transitions and integrity protections to the V56 prompt schema. Main's V55–V57 migrations are unchanged. A NOT VALID hash constraint preserves pre-existing V57 rows that were allowed to contain a caller-supplied hash; new rows must have an accurate hash, and resolving a mismatched stored revision fails closed. The 16,000 character operational limit is accounted for in the provider's composed prompt bound. The legacy creation and activation paths now apply the deterministic prompt validator as well.

`DatabaseMigrationTest` includes a populated V57-to-V58 upgrade, and `ClassificationPromptLifecycleTest` verifies old and new APIs share active state, audit history, and reset behavior. The prior V55 issue #45 migration was removed to avoid duplicate Flyway versions and duplicate tables. New history uses the #46 state version as its ordering key. Existing checksum and audit identity values are never rewritten.

Verification using JDK 25, Maven 3.9.11 and host Testcontainers:

- `/tmp/issue45-merge-tests-final.log`: 130 migration, prompt repository/service, provider, Shadow acceptance and startup tests passed (0 failures/errors/skips), before the final V58 legacy-upgrade refinement.
- `/tmp/issue45-merge-upgrade.log`: 31 migration and PostgreSQL lifecycle/repository tests passed (0 failures/errors/skips), including a populated V57 upgrade after that refinement.
- `/tmp/issue45-merge-main46.log`: 43 PR #46 Telegram, settings and calibration tests passed (0 failures/errors/skips).
- `/tmp/issue45-merge-hash-final.log`: 46 tests passed (0 failures/errors/skips), covering fail-closed hash handling and its lifecycle, calibration and Telegram consumers.

Remaining integration context for Task 3: main already resolves the same snapshot for both providers and persists prompt revision/hash. Review its existing stored-evaluation ordering and failure behavior against the Task 3 brief before treating that task as complete. Full `mvn verify` remains the final integration gate.

A database that previously applied this unmerged feature branch's original V55 will have a conflicting Flyway V55 history against main. The merged migration sequence targets databases following main; such an experimental database needs a separately reviewed repair or rebuild rather than silently changing Flyway history.
