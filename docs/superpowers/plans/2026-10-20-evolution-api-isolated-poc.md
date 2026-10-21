# Evolution API Isolated POC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Prove or disprove that a pinned Evolution API distribution can reliably expose the real archived state and identity of archived WhatsApp chats without modifying archive state, interrupting the production watcher, or sending automated messages.

**Architecture:** Run a disposable, isolated Evolution API instance with its own Redis, session, database and a metadata-only Node.js webhook collector. Collect reproducible, redacted evidence for startup/history, archive transitions, messages, LID/PN, reconnection and transport failures. The official distribution is evaluated first; only a documented failure permits testing the fixed-commit fork with the same tests. This POC does **not** implement the Spring backend.

**Tech Stack:** Existing repository Node.js 24 / TypeScript 5.9 / node:test; Docker Compose; official Evolution API tag 2.3.7 (tag points to source commit cd800f2976e1e5b682fbf86a01ee4d85ae61f370); Redis as required by its pinned distribution; existing PostgreSQL server with separate logical DB/role; no new production services.

**Spec:** docs/superpower/spec/2026-10-16-archived-watcher-automation-design.md (PR #2).

## Global Constraints

- Production remains the existing TypeScript watcher using Baileys 7.0.0-rc14, watcher.db and n8n; do not change src/whatsapp.ts, its session/auth, compose.yaml, n8n, or existing volumes/network attachments.
- This plan only authorizes **creating the POC code and documentation after plan approval**. Connecting a real WhatsApp account, provisioning on the VPS, installing services and linking a device require an explicit operational go/no-go immediately before that step. POC is never cutover.
- The Evolution API is read-only with respect to WhatsApp archive state: never call archiveChat, Baileys chatModify or any archive/unarchive API; change archive state only manually in the WhatsApp client.
- Check **official first**. Use the pinned official source tag 2.3.7, then only evaluate an additional official release if the release and reason are recorded. The 2.4.0-rc2 line is a pre-release and has licensing considerations, not an automatic production replacement. Fork fallback is AlmogCohen/evolution-api branch fork/2.3.7, resolved to a full commit at execution time.
- Never use Docker image tag latest. Record the OCI digest actually executed, matching source commit/version, build provenance, available auth controls and licensing before pairing. If no verifiable image exists, build from exact pinned source; do not guess tag-to-image equivalence.
- Reuse physical PostgreSQL instance only after inspecting existing capacity, grants, backups and Docker network; give the POC its **own DB/role**. Redis/session/data volumes must also be exclusive to POC; do not start a second PostgreSQL service by default.
- No RabbitMQ, Kafka, GraalVM, Spring AOT or custom Baileys gateway. No automatic sending of WhatsApp messages or Telegram individual notifications from this POC.
- Treat archived state absent/ambiguous as UNKNOWN, never false; missing initial snapshot/reconciliation after restart **fails** the POC. Webhook delivery guarantees must be observed, not inferred from configurable retry variables.
- No raw message text, media, phone numbers, contact names, identifiers, QR codes, session keys, tokens or credentials in committed files, logs, fixtures or reports. Evidence is limited to sanitized shapes, presence/boolean flags and salted hashes. Private captured data expires within 7 days; default report contains no identifiers.
- Preserve source-of-truth boundaries: Evolution owns WhatsApp integration only after a future, independently approved cutover; n8n continues delivering current watcher notifications until then.

## Review Focus

1. An event without an archived property must produce UNKNOWN, never silently become archived=false (Task 2 test).
2. Group events from different senders must be distinguishable without persisting sender identifiers or message text (Task 2 test).
3. Repeated webhook delivery must not inflate unique-event evidence or be mistaken for a guaranteed transport retry (Task 4 test).
4. A malformed, oversized or unauthorized webhook must not write raw or sanitized event records (Task 3 test).
5. A manually marked PASS without cited proof of startup/restart archived reconciliation or an unexplained disconnect must not promote the candidate (Task 4 test).

## Bounded deliverable and next plan boundaries

This plan stops at an evidence-backed GO / NO-GO decision on Evolution integration. Do not scaffold Spring Boot until it passes. Later, separate plans will implement: (a) Spring Boot 4.x/Java 25 JVM + PostgreSQL/Flyway inbox/jobs/outbox; (b) snapshot/identity/debounce/Shadow Mode and policy engine; (c) Gemini/OpenRouter, privacy and budget; (d) Telegram admin + n8n compatibility; (e) opt-in real actions, migration and rollback. Their precise webhook DTOs depend on **captured versioned POC fixtures**. Next.js SSR remains out of scope.

## File structure and ownership

- Create src/poc-evolution/config.ts: parse and reject unsafe POC configuration.
- Create src/poc-evolution/observations.ts: convert **documented or captured** version-specific event payloads to redacted observations; expose UNKNOWN for missing fields.
- Create src/poc-evolution/collector.ts: isolated HTTP ingress, size/schema/auth checks and durable metadata-only NDJSON output.
- Create src/poc-evolution/collector-cli.ts: explicit process entry point, never start the legacy watcher.
- Create src/poc-evolution/decision.ts: evaluate versioned operator scenarios and sanitized observations; produce evidence-based GO/NO-GO.
- Create src/poc-evolution/decision-cli.ts: read evidence and emit redacted Markdown/JSON report.
- Create tests/poc-evolution-config.test.ts, tests/poc-evolution-observations.test.ts, tests/poc-evolution-collector.test.ts and tests/poc-evolution-decision.test.ts.
- Create poc/evolution/compose.yaml, poc/evolution/Dockerfile.collector, poc/evolution/.env.poc.example and poc/evolution/README.md: strictly isolated runtime and operator procedure.
- Create poc/evolution/scenarios.json: **empty scenario records**, no fabricated results; populate from observation only after actual tests.
- Create poc/evolution/decision-template.md: final evidence/risks/decision structure.
- Modify .gitignore and .dockerignore **only** to exclude POC secrets, captures, QR/session artifacts, and local evidence.
- Do not modify the current production compose.yaml, Dockerfile, package dependencies or current watcher code.

---

### Task 1: Isolated preflight and configuration gate

**Files:**
- Create: src/poc-evolution/config.ts
- Create: tests/poc-evolution-config.test.ts
- Modify: .gitignore; .dockerignore

**Interfaces:**
- Produces: validatePocConfig(input: PocConfig): string[] where PocConfig contains imageReference, databaseName, postgresRole, dockerNetworkNames, volumeMounts, outputDir and sourceCommit.
- Produces: isImmutableOciImage(reference: string): boolean (accept only image@sha256: followed by 64 hex digits); string errors are stable codes.

- [ ] **Step 1: Write failing tests** for mutable/latest image, empty source commit, same DB/role as a user-supplied protected resource, mount under legacy data/auth, legacy compose path or infra_network collision, and valid fully-isolated input; assert exact error codes. The test uses synthetic values and no real secrets.
- [ ] **Step 2: Run red:** npm run build && node --test dist/tests/poc-evolution-config.test.js; expected: failure before implementation.
- [ ] **Step 3: Implement the two exports** with no network side effects. Validate outputDir under dedicated POC workspace and reject POC environment paths outside it. Update ignore files for poc/evolution/.env.poc, poc/evolution/private/, poc/evolution/evidence/ and session artifacts; do not ignore scenarios.json or the decision template.
- [ ] **Step 4: Run green:** npm run typecheck && npm run build && npm test; expect exit 0 and every new configuration assertion PASS.
- [ ] **Step 5: Commit:** git add src/poc-evolution/config.ts tests/poc-evolution-config.test.ts .gitignore .dockerignore && git commit -m "test(poc): guard isolated Evolution configuration"

### Task 2: Redacted, versioned event normalization

**Files:**
- Create: src/poc-evolution/observations.ts
- Create: tests/poc-evolution-observations.test.ts

**Interfaces:**
- Consumes: inbound JSON as unknown, event header if present, known pinned distribution identifier and receive timestamp.
- Produces: normalizePocEvent(payload: unknown, context: { distribution: string; receivedAt: string; hashSalt: string }): PocObservation[].
- PocObservation fields: distribution, eventType, receivedAt, archiveStatus (TRUE | FALSE | UNKNOWN), eventKind (CHAT | MESSAGE | CONNECTION | OTHER), chatKind (DM | GROUP | UNKNOWN), chatKeyHash?, participantKeyHash?, messageKeyHash?, hasLid, hasPn, hasRemoteJidAlt, statusHint?, pathOfArchivedField?; **never** raw identifiers, text, media, names or payload.

- [ ] **Step 1: Write failing tests** for a synthetic chats.update archived=true, archived=false, omitted/null archived => UNKNOWN, chats.set with several chats, contact @lid and PN alias presence, group events with distinct sender hashes, duplicate same message producing stable salted hash, message body/PII never appearing in serialized observation, unknown payload/event keeping UNKNOWN. Synthetic fixtures are NOT evidence of actual Evolution behavior.
- [ ] **Step 2: Run red:** npm run build && node --test dist/tests/poc-evolution-observations.test.js; expect failure.
- [ ] **Step 3: Implement the converter** using only payload shapes established by the tagged official documentation or captured sanitized schema; where a field path is not verified, preserve UNKNOWN. No phone-number guesses from @lid, and no message contents in output. Use keyed SHA-256/HMAC with a private local salt for unstable IDs.
- [ ] **Step 4: Run green:** npm run typecheck && npm run build && npm test; all tests pass and snapshot strings contain no private sample text. When real POC events arrive, add redacted version-specific fixture assertions BEFORE treating that event mapping as proven.
- [ ] **Step 5: Commit:** git add src/poc-evolution/observations.ts tests/poc-evolution-observations.test.ts && git commit -m "test(poc): normalize archived and identity evidence safely"

### Task 3: Webhook capture without application actions

**Files:**
- Create: src/poc-evolution/collector.ts
- Create: src/poc-evolution/collector-cli.ts
- Create: tests/poc-evolution-collector.test.ts

**Interfaces:**
- Consumes: normalizePocEvent from Task 2; a caller-provided admission function tied to verified actual sender capability OR Docker-private ingress restrictions (do not assume native HMAC/auth headers).
- Produces: createPocCollector(options: { outputFile: string; maxBodyBytes: number; admit: (request: IncomingMessage) => boolean; distribution: string; hashSalt: string }): Server.
- CLI starts only the collector on a POC-specific bind address and records redacted observations in POC-private storage; never imports src/index.ts.

- [ ] **Step 1: Write failing HTTP tests** using ephemeral port: accepted POST persists only sanitized NDJSON; bad JSON returns 400; oversized body returns 413; unsupported route returns 404/405; rejected source returns 401/403; all rejected requests leave output unchanged; two simultaneous writes remain well-formed; disk failure returns non-2xx rather than acknowledging silently.
- [ ] **Step 2: Run red:** npm run build && node --test dist/tests/poc-evolution-collector.test.js; expect failure.
- [ ] **Step 3: Implement minimum collector** with request-size limit (64 KiB initial POC default), bounded disk writes, request-specific failure handling and no outbound WhatsApp/n8n calls. Avoid dumping raw request bodies to logs/errors. The HTTP 2xx ACK must occur only after the observation is durably written; include an explicit fsync/flush policy and test its error path.
- [ ] **Step 4: Run green:** npm run typecheck && npm run build && npm test; assert rejected input did not change evidence and collected events contain no message text.
- [ ] **Step 5: Commit:** git add src/poc-evolution/collector.ts src/poc-evolution/collector-cli.ts tests/poc-evolution-collector.test.ts && git commit -m "feat(poc): capture private webhook metadata without side effects"

### Task 4: Gate evaluator, dedup and report

**Files:**
- Create: src/poc-evolution/decision.ts
- Create: src/poc-evolution/decision-cli.ts
- Create: tests/poc-evolution-decision.test.ts
- Create: poc/evolution/scenarios.json
- Create: poc/evolution/decision-template.md

**Interfaces:**
- Produces: evaluatePoc(candidate: CandidateRecord, observations: PocObservation[]): PocDecision.
- CandidateRecord has sourceRepo, pinnedCommit, imageDigest, databaseIsolationVerified, privateNetworkVerified, noArchiveApiCallsVerified, pairingIsolationVerified and scenarios[]; each scenario has id, observedStatus (PASS | FAIL | UNKNOWN), evidenceReferences[] and humanNotes. PocDecision has verdict (GO | NO_GO), blockingReasons[], warnings[], candidateKey.
- Scenario IDs: archived_initial_dm, archived_initial_group, manual_archive_true, manual_unarchive_false, restart_reconciliation, reconnect_reconciliation, new_dm_message, new_group_message, lid_pn_alias, renamed_contact, duplicate_delivery, message_ordering, webhook_auth, webhook_transient_failure, session_stability, no_archive_api_calls, legacy_unchanged. A scenario is not PASS without referenced sanitized evidence.

- [ ] **Step 1: Write failing tests** for all UNKNOWN/missing scenarios giving NO_GO; PASS without evidence giving NO_GO; fake archived state originating only in POC DB giving NO_GO; a single unexplained disconnect/device_removed giving NO_GO; duplicated same hashed event not counting twice; true fully supported synthetic record giving GO. The synthetic GO test is evaluator logic, never real POC approval.
- [ ] **Step 2: Run red:** npm run build && node --test dist/tests/poc-evolution-decision.test.js; expect failure.
- [ ] **Step 3: Implement evaluator and CLI** with stable sorting, dedup by distribution/event-type/hashed message key and no raw fields. scenarios.json starts UNKNOWN across all IDs. decision-template.md lists version, provenance, observations, blockers, security, retained data and operator signature/date; do not prefill positive findings.
- [ ] **Step 4: Run green:** npm run typecheck && npm run build && npm test; running the CLI against untouched scenarios.json must report NO_GO and list missing evidence, with nonzero exit when used in CI gate.
- [ ] **Step 5: Commit:** git add src/poc-evolution/decision.ts src/poc-evolution/decision-cli.ts tests/poc-evolution-decision.test.ts poc/evolution/scenarios.json poc/evolution/decision-template.md && git commit -m "test(poc): gate Evolution choice on reproducible evidence"

### Task 5: Compose isolated runner and static safety checks

**Files:**
- Create: poc/evolution/compose.yaml
- Create: poc/evolution/Dockerfile.collector
- Create: poc/evolution/.env.poc.example
- Create: poc/evolution/README.md
- Create: tests/poc-evolution-compose.test.ts

**Interfaces:**
- Consumes: Task 1 validation; Task 3 collector CLI; EVOLUTION_POC_IMAGE (immutable OCI digest only), a private DB URL/role, exclusive Redis and session volume, POC-specific network and operator-provided test instance name.
- Produces: Compose config for evolution-poc, evolution-poc-redis, poc-collector; NO separate PostgreSQL server and NO n8n/watcher service.

- [ ] **Step 1: Write failing static test** ensuring Compose has no default production networks, no watcher/data/auth mount, no host-published Redis/collector ports, no latest image, distinct named POC volumes, no use of existing n8n webhook, and collector command is explicitly collector-cli. Assert DB vars are supplied through dedicated noncommitted .env.poc rather than literals.
- [ ] **Step 2: Run red:** npm run build && node --test dist/tests/poc-evolution-compose.test.js; expect failure.
- [ ] **Step 3: Add Compose, Dockerfile and runbook**. Reuse a dedicated DB/role on the existing PostgreSQL host (no cross-application permissions), Redis isolated to POC, collector private to POC network; expose Evolution management UI only through a temporary loopback/SSH tunnel if demonstrably needed. Document provisioning inspection, backup verification, image digest provenance, account/device pairing gate, license requirement check, private file permissions, volume cleanup/retention and exact teardown procedure that **never removes production resources**.
- [ ] **Step 4: Run green:** npm run typecheck && npm run build && npm test. Then with safe locally supplied fake nonsecret env, run docker compose --env-file poc/evolution/.env.poc -f poc/evolution/compose.yaml config --quiet; expect exit 0. Never use docker compose up in this step. If Docker is unavailable, report validation BLOCKED rather than claim pass.
- [ ] **Step 5: Commit:** git add poc/evolution/compose.yaml poc/evolution/Dockerfile.collector poc/evolution/.env.poc.example poc/evolution/README.md tests/poc-evolution-compose.test.ts && git commit -m "build(poc): isolate Evolution trial from legacy watcher"

### Task 6: Official trial, conditional fork and decision gate (operator-assisted)

**Files:**
- Modify: poc/evolution/scenarios.json
- Create: poc/evolution/evidence/ (LOCAL/IGNORED; never commit)
- Create: poc/evolution/decision.md (sanitized, only after observation; otherwise use decision-template.md)
- Modify: poc/evolution/README.md (only findings/procedure differences actually observed)

**Interfaces:**
- Consumes: all prior tasks, pin/source provenance, sanitized observation logs and manual operator scenario attestations.
- Produces: report with either official GO, fork GO after official NO_GO, or migration NO_GO; no implicit permission for Spring, WhatsApp auto-reply or cutover.

- [ ] **Step 1: Verify preflight and obtain the separate pairing/VPS go/no-go.** Identify exact official tag 2.3.7 commit cd800f2976e1e5b682fbf86a01ee4d85ae61f370 and executable immutable image digest; prove correct version by runtime metadata, not tag name alone. Check free CPU/RAM and backups, database isolation, permission and network separation. Without approval or capacity, record BLOCKED and STOP.
- [ ] **Step 2: Start only isolated POC resources** per the documented Compose instructions. Confirm legacy watcher uptime and unchanged data/sessions; do not restart, relink, unarchive or log into the legacy production account. Pair only the explicitly approved test device/account with manual action; check license/activation requirements before making any network-exposed endpoint accessible.
- [ ] **Step 3: Execute each scenario** by manual WhatsApp archiving/unarchiving for test chats, then receiving test messages, renaming a contact, restart/reconnect, simulating webhook 5xx/timeout, duplicate/reordered delivery and logging link-device stability. Capture observed events and actual delivery behavior; verify that **zero archiveChat/chatModify calls** occurred. Raw evidence is kept only in ignored private storage and purged after at most 7 days. Redact report references.
- [ ] **Step 4: Evaluate official candidate.** Run npm run typecheck && npm run build && npm test, then node dist/src/poc-evolution/decision-cli.js with POC-private evidence and sanitized scenarios. A GO requires ALL mandatory gates, known startup+restart archived states, LID/PN mapping and recovery of critical events. A missing field/retry guarantee or unexplained session disconnection forces NO_GO. Do not equate editable internal state with WhatsApp archival truth.
- [ ] **Step 5: Conditional fork trial.** Only when official NO_GO evidence is recorded, resolve full commit of AlmogCohen/evolution-api fork/2.3.7, review code provenance/security/licensing and repeat the **identical scenarios** in isolated resources with a new, versioned evidence set. Its claimed archived webhook fix does not establish persisted snapshot correctness. If fork also fails, STOP migration and preserve the current watcher.
- [ ] **Step 6: Record the final decision** in sanitized poc/evolution/decision.md with source URL, tested commit, exact image digest, testing date/time, result per scenario, retry observations, unresolved concerns and explicit GO/NO_GO. Commit report without user data: git add poc/evolution/scenarios.json poc/evolution/README.md poc/evolution/decision.md && git commit -m "docs(poc): record Evolution compatibility decision". Keep private evidence and credentials untracked. Tear down only dedicated POC resources after retaining minimal decision artifacts.

## Verification and completion boundary

- Full repository verification: npm run typecheck && npm run build && npm test (using the existing dist-based node:test workflow).
- Runtime config verification: docker compose --env-file poc/evolution/.env.poc -f poc/evolution/compose.yaml config --quiet (with safe, local, noncommitted config); image must be immutable by digest; no running container proves anything before manual observation.
- Risk/PII verification: git diff --check, git status --short, git grep for key/token/session paths in changed tracked files, inspect full git diff; do not print secrets in CI output. The existing watcher’s behavioral tests must still pass without changes.
- No claim of success from synthetic fixtures. A decision report may say GO only after an authorized, versioned, observed POC; absent account access or human pairing, completion is limited to **POC harness ready, live validation blocked**.
- After this plan is executed and reviewed, draft the separate Spring plan using actual redacted payload contract(s), then request approval **again** before code or production changes. Do not merge this documentation PR or deploy anything automatically.

## Sources and evidence to recheck at execution

- Official Evolution repository and pinned 2.3.7 commit: https://github.com/evolution-foundation/evolution-api/tree/2.3.7
- Release/prerelease and licensing caveat: https://github.com/evolution-foundation/evolution-api/releases
- Official event categories: https://github.com/evolution-foundation/evolution-docs/blob/main/docs/02-Configuration/Webhooks.md
- archived field issue: https://github.com/evolution-foundation/evolution-api/issues/2743
- archiveChat disconnection report (correlation, not proof): https://github.com/evolution-foundation/evolution-api/issues/2495#issuecomment-6273731128
- Conditional fork: https://github.com/AlmogCohen/evolution-api/tree/fork/2.3.7
- PostgreSQL worker design is **next plan**, not this POC: https://www.postgresql.org/docs/current/sql-select.html

**Execution handoff:** Review this plan and explicitly choose subagent-driven or native execution. No implementation, VPS access, WhatsApp account pairing, merge, or deploy is authorized by writing this plan alone.
