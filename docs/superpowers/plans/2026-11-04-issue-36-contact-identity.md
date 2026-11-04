# Issue #36 Contact Identity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prefer proven owner contact names, then the account's available WhatsApp profile name, with an optional local alias only as a last fallback; send a minimized, typed sender envelope to AI providers without claiming that ambiguous Evolution `pushName` values came from the owner's address book.

**Architecture:** Preserve Evolution observations as `NOTIFY`; add a distinct `manual_display_name` field as an explicit local alias, so clearing it reveals the saved contact/profile fallback rather than losing that value. Manage it through the existing identity aggregate and confirmed private Telegram admin commands. Resolve each message's sender from its existing canonical identity and serialize role, safe display name, local opaque sender token, and text into provider input, keeping group and owner attribution separate.

**Tech Stack:** Java 25, Spring Boot 4, PostgreSQL 17, Flyway, Maven, existing Telegram admin/outbox flow, JUnit 5/Testcontainers.

**Spec:** `docs/superpower/specs/2026-11-04-issue-36-contact-identity-design.md`

## Global Constraints

- Evolution API 2.3.7 does not preserve whether `contact.name` or `contact.verifiedName` produced `pushName`; keep these observations `NOTIFY`.
- Resolve identities only by canonical JID and aliases with explicit PN/LID pair evidence; never merge or authorize by name.
- Never send raw JID/phone, contact lists, unrelated group metadata, or untrusted text as instructions to a provider.
- Keep classification consent, cost, retention, kill-switch, and real-action gates unchanged; do not enable real sends or modify production configuration.
- Use forward-only Flyway migrations and keep SQL inside repositories.
- Preserve the seven-day maximum sensitive-content retention and do not reclassify history.
- Telegram admin mutations remain restricted to the configured private administrator and use existing confirmation/audit behavior.

## Review Focus

- A `pushName` profile name must override an optional owner alias; clearing the alias must preserve the latest profile value. Test both through `IdentityServiceTest`.
- An unlinked PN and LID or two participants with the same name must stay distinct. Test identity lookup and group context projection.
- The owner must not be labeled as a group participant, and unrelated group participants must not enter context. Test `ClassificationInput` and `ConversationContextRepository`.
- Technical names and malicious HTML/Unicode must fall back or render safely. Test identity validation and Telegram query formatting.
- Provider JSON must omit raw JIDs/phones while retaining a typed role and per-context opaque ID. Assert serialized HTTP request payload in provider tests.

---

### Task 1: Explicit manual alias provenance and mutation

**Files:**
- Modify: `src/main/java/dev/hugo/watcher/ContactNameSource.java`
- Modify: `src/main/java/dev/hugo/watcher/IdentityService.java`
- Modify: `src/main/java/dev/hugo/watcher/IdentityMergeRepository.java`
- Create: `src/main/resources/db/migration/V54__manual_contact_display_names.sql`
- Test: `src/test/java/dev/hugo/watcher/IdentityServiceTest.java`

**Interfaces:**
- Produces: `IdentityService.setManualAlias(String jid, String alias)` and `IdentityService.clearManualAlias(String jid)`; both resolve only the supplied JID through existing canonical aliases and preserve PN/LID merge rules.
- Produces: an explicit `manual_display_name` identity field, used only when proven `CONTACT_NAME` and WhatsApp profile `NOTIFY` are unavailable. The existing `display_name` and source remain intact underneath.

- [ ] Write tests proving `CONTACT_NAME` and `NOTIFY` beat a manual alias, clearing it reveals the next valid name, rename survives explicit PN/LID merge, and invalid/technical aliases are rejected.
- [ ] Run `mvn -B -ntp -Dtest=IdentityServiceTest test`; confirm the new cases fail for missing `MANUAL` behavior.
- [ ] Add the manual field, repository operations, transactional service methods, and a forward-only Flyway migration.
- [ ] Re-run `mvn -B -ntp -Dtest=IdentityServiceTest test`; confirm passing behavior and existing identity merge tests.

### Task 2: Private Telegram alias command and display precedence

**Files:**
- Modify: `src/main/java/dev/hugo/watcher/telegram/AdminCommandService.java`
- Modify: `src/main/java/dev/hugo/watcher/telegram/TelegramAdminPersistenceRepository.java` only if a display query needs repository support
- Modify: `src/main/java/dev/hugo/watcher/NotificationService.java`
- Modify: `src/main/java/dev/hugo/watcher/telegram/ShadowDecisionQuery.java`
- Test: `src/test/java/dev/hugo/watcher/telegram/AdminCommandServiceTest.java`
- Test: `src/test/java/dev/hugo/watcher/NotificationServiceTest.java`
- Test: `src/test/java/dev/hugo/watcher/telegram/ShadowDecisionQueryTest.java` (or existing covering test)

**Interfaces:**
- Consumes: `IdentityService.setManualAlias` and `clearManualAlias` from Task 1.
- Produces: `/apelido <jid> <nome...>` and `/apelido <jid> remover`, with multiword Unicode names and existing confirmation/audit protections.

- [ ] Add tests for set/rename/remove commands, confirmation, malformed arguments, HTML-like names, and rejection of unconfirmed mutation.
- [ ] Run the focused Telegram test and confirm the new command is not handled.
- [ ] Route the command through `IdentityService`; keep JID only in the private command path and never echo it into provider context or public notifications.
- [ ] Add display tests proving direct-chat name priority in new-message/last/decsion/shadow views, group name remains the group label, and participant attribution is distinct where a message preview includes it.
- [ ] Run the focused admin, notification, and decision-query test classes.

### Task 3: Typed, minimized AI sender envelope

**Files:**
- Modify: `src/main/java/dev/hugo/watcher/ai/ClassificationInput.java`
- Modify: `src/main/java/dev/hugo/watcher/ai/ConversationContextRepository.java`
- Modify: `src/main/java/dev/hugo/watcher/ai/AbstractHttpClassificationProvider.java`
- Modify: `src/main/java/dev/hugo/watcher/ai/ShadowMetrics.java`
- Test: `src/test/java/dev/hugo/watcher/ai/ClassificationInputMinimizerTest.java`
- Test: `src/test/java/dev/hugo/watcher/ai/ConversationContextRepositoryTest.java`
- Test: existing OpenRouter and Gemini provider tests

**Interfaces:**
- Produces: structured `ContextEntry(role, text, senderName, senderId)` where `role` is `owner`, `contact`, or `group_participant`; `senderName` is null unless a valid friendly identity name is available; `senderId` is an opaque per-payload value and never a JID.
- Produces: provider request JSON where both target and context entries carry typed role/sender metadata; text remains explicitly untrusted in the system prompt.

- [ ] Add tests for direct contact attribution, owner replies, same-name group participants with distinct opaque IDs, neutral fallback, Unicode, and no raw PN/LID/phone strings in serialized requests.
- [ ] Run the focused AI tests and confirm the required sender metadata is absent today.
- [ ] Resolve participant display names through the existing canonical identity service and assign deterministic local IDs within each context payload without persisting or sending JIDs.
- [ ] Update provider payload serialization and bounded metric counting; metrics must continue to exclude names, IDs, and text.
- [ ] Run the focused AI/provider suites, including the #32 context and group owner reply cases.

### Task 4: Evolution contract and user documentation

**Files:**
- Modify: `docs/evolution-contact-names.md`
- Modify: `README.md`
- Test: `src/test/java/dev/hugo/watcher/EvolutionPipelineIntegrationTest.java`

**Interfaces:**
- Documents the pinned v2.3.7 evidence: `contacts.upsert` uses `name || verifiedName || jid fragment`; `contacts.update` uses `name ?? verifiedName`; neither retains provenance.
- Documents that multi-device contact delivery is not proof of complete or current owner address book synchronization, and describes private `/apelido` management without claiming it is synced.

- [ ] Add a sanitized fixture test showing the contact event `pushName` is stored only as `NOTIFY` and remains below `CONTACT_NAME` but above a manual alias.
- [ ] Run the focused Evolution integration test and confirm that the source-priority regression is caught.
- [ ] Update the contract note and README with the priority order (proven contact name, account profile name via `pushName`, optional local alias), synchronization limits, command syntax, context envelope, minimization, and no-history-reclassification guarantee.
- [ ] Run the focused Evolution pipeline test.

### Task 5: Full acceptance and PR preparation

**Files:**
- Review all files changed in Tasks 1–4.

- [ ] Run `python3 -B -m unittest discover -s tests/ci -p 'test*.py'`.
- [ ] Run `mvn -B -ntp verify`; report Testcontainers cases skipped if Docker is unavailable.
- [ ] Review the diff for raw JIDs in provider requests, name-based identity/authorization, altered real-action gates, logs containing personal data, and migration compatibility.
- [ ] Create a feature branch PR targeting `main` with `Closes #36`; do not merge, push to `main`, or deploy.
