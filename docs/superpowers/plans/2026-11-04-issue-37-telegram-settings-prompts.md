# Issue #37 Telegram Settings and Prompt Revisions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver Telegram-native operational settings and safe, versioned Shadow classification prompts for issue #37.

**Architecture:** Keep admin transport in the existing Telegram service and authorize every update through the current private-admin handler. Add a typed runtime-settings catalog and append-only PostgreSQL history only for explicitly allowed values; keep existing policy, budget, availability and emergency stores authoritative. Store immutable prompt revisions separately and compose each provider request from a fixed security core plus the active operational section, recording the revision on each decision.

**Tech Stack:** Java 25, Spring Boot 4, Spring Data JDBC/JdbcTemplate repository boundary, PostgreSQL 17, Flyway, Telegram Bot API, JUnit 5/Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-04-issue-37-telegram-settings-prompts-design.md`

## Global Constraints

- Do not edit `.env`, Compose, credentials, internal endpoints, real-send flags or emergency gates.
- Keep `WATCHER_SIMULATION_ENABLED=false` as a hard ceiling and preserve fail-closed consent, budget, privacy, kill-switch and emergency checks.
- Do not store raw callback actions, secrets or prompt bodies in logs; callbacks contain opaque tokens only.
- Flyway migrations are additive; SQL remains in repository classes and never enters controllers/services.
- Prompt revisions are immutable; only the operational section is user-editable and activation is Shadow-only.
- No automated test may call paid AI or a real Telegram/WhatsApp endpoint; provider transport and time are faked where required.
- Preserve issue #31/#33 triage ownership and avoid duplicate policy data.

## Review Focus

- Environment ceiling or emergency says disabled while Telegram requests enablement — test explicit refusal and unchanged effective state.
- Replayed, expired, wrong-user or wrong-chat callback — test rejection and no mutation.
- Concurrent prompt activation or settings write — test serialized/version-checked result and single active prompt.
- Invalid, oversized, malicious prompt or placeholder — test rejection and that no prompt body enters logs/audit.
- Database outage or absent AI credentials during prompt preview — test fail-closed behavior and no fallback that skips budget/authorization.

---

### Task 1: Typed runtime settings and revision history

**Files:**
- Create: `src/main/java/dev/hugo/watcher/telegram/RuntimeSettingKey.java`, `RuntimeSettingDefinition.java`, `RuntimeSettingsService.java`, `RuntimeSettingsRepository.java`
- Create: `src/main/resources/db/migration/V55__telegram_runtime_settings.sql` (V54 already exists on the base branch)
- Create: focused repository/service tests under `src/test/java/dev/hugo/watcher/telegram/`
- Test: `RuntimeSettingsServiceTest`, repository PostgreSQL integration test

**Interfaces:** `RuntimeSettingsService.list()` returns safe metadata/value/origin/editability/blockers; `set(key,value,expectedVersion,actor,at)` validates and appends history atomically; `restore(key,actor,at)` clears only that key's override.

- [x] Add tests for allowlisted typed values, range enforcement, environment ceiling, optimistic version conflict, history/actor/timestamp and restore.
- [x] Add append-only audit/history tables and repository methods with optimistic version checks.
- [x] Implement the catalog. Reuse existing repositories for shadow pause, global/chat policy, budget and availability instead of mirroring them.
- [ ] Run focused Java tests and repository boundary tests (blocked: Maven is not installed in this environment).

### Task 2: Effective configuration views and safe editing

**Files:**
- Modify: `AdminCommandService.java`, `TelegramAdminPersistenceRepository.java`, `OperationalState.java` or narrowly scoped provider for configured context ceiling
- Create: configuration view/menu contract and tests
- Test: `AdminCommandServiceTest`, `OperationalStateTest`

**Interfaces:** section read models include configured/effective value, source, editable flag, dependency and reason blocked; no secret values or prompt text in audit.

- [x] Add coverage for section contents and restrictive environment behavior; Shadow writes remain bounded by the environment ceiling.
- [x] Implement section navigation for General/Shadow, AI/models, Notifications/triage, Profiles/contacts, Availability, Templates, Budget/limits, Security and Status/audit.
- [x] Add confirmed runtime edit and restore flows for the explicitly allowlisted context window; preserve existing commands and aliases.
- [x] Add `/contato <jid>` read view using existing effective-policy/availability services and SQL repository projections for authorizations/current consent.
- [ ] Run focused Java tests for authorization, callbacks, command handling and persistence (blocked: Maven is not installed).

### Task 3: Immutable prompt core and revision lifecycle

**Files:**
- Create: `ai/ClassificationPromptService.java`, prompt revision model/repository and additive migration V56
- Modify: `AbstractHttpClassificationProvider.java`, `OpenRouterProvider.java`, `GeminiProvider.java`, provider bean wiring
- Test: `ClassificationPromptServiceTest`, `OpenRouterProviderTest`, `GeminiProviderTest`, prompt repository integration test

**Interfaces:** `ClassificationPromptService.current()` returns `{revisionId, revision, hash, composedText}`; draft/activate/restore operations validate content and use atomic active-revision compare-and-set.

- [x] Add tests for fixed core composition, default prompt, bounded size, unsupported placeholder rejection, malicious/invalid text rejection, history and single-active revision.
- [x] Add provider payload tests and persist revision/hash alongside classification decisions.
- [x] Implement immutable prompt revisions and active pointer with author/time/version and rollback; never log prompt text.
- [x] Wire both providers to resolve the prompt per classification; DB resolution failures return unavailable.
- [x] Persist prompt revision identifier/hash using additive schema changes.
- [ ] Run provider, classification, persistence and migration tests with fake transport (blocked: Maven is not installed).

### Task 4: Telegram prompt management and calibration UX

**Files:**
- Modify: `AdminCommandService.java`, `TelegramUpdateHandler.java`, callback/action types as needed
- Create: `PromptCalibrationService.java`, durable calibration queue repository/worker and sanitized fixture factory
- Test: Telegram handler/admin service regression tests

**Interfaces:** prompt flow supports list/current, create draft from a bounded private message, compare revisions, activate and restore by reactivating an immutable prior revision; long content is received as a normal authorized private message, never callback data.

- [x] Add admin/private-chat handler tests, draft size/unsupported placeholders, one-use/expiry, confirmation and bounded comparison coverage.
- [x] Implement paged prompt navigation and a bounded prompt-input state bound to user/chat/expiry; redact body from logs/audit.
- [x] Implement explicit prompt activation/rollback confirmations and success/error responses with navigation.
- [x] Implement confirmed, rate-limited calibration on a synthetic fixture; require active Shadow, configured OpenRouter route and normal budget reservation. A committed claim precedes provider HTTP; ambiguous work becomes UNKNOWN without retry. Output reports classification, triage, gates and non-evaluated contact-specific controls.
- [ ] Run Telegram/admin and prompt Java tests (blocked: Maven is not installed).

### Task 5: Acceptance regressions, docs and full verification

**Files:**
- Modify: `README.md` and/or `docs/` user/operator guidance
- Test: existing Telegram, AI, migration, policy-boundary and full test suite

- [x] Add regression tests for durable storage, concurrency/version conflicts, callback replay/expiry, restrictive env, emergency gates and malformed prompts.
- [x] Document the catalog, ceilings, prompt lifecycle, calibration limits and contact policy overview in this spec.
- [ ] Run `python3 -B -m unittest discover -s tests/ci -p 'test*.py'` and `mvn -B -ntp verify`; Maven is currently unavailable.
- [ ] Review `git diff`, acceptance checklist and repository boundaries; commit this branch and open a PR containing `Closes #37` after available verification.

## Self-review

- Coverage: menu metadata/operations (Task 2); typed persistence and audit (Task 1); immutable prompt/version/use-by-decision (Task 3); callbacks, text-input, calibration and rollback (Task 4); acceptance and docs (Task 5).
- Interface consistency: repository and prompt service contracts are defined above; UI consumes read models and lifecycle methods only.
- Review-focus tests are assigned to Tasks 1–4; full persistence/migration checks are included in Tasks 1, 3 and 5.
- Migration numbering must be rechecked against the latest committed migration before authoring SQL.
