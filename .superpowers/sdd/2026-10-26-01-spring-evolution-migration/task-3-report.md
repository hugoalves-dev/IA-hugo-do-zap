# Task 3 — Evolution contract and identity

## Resultado

- `EvolutionEventMapper.map(JsonNode, String distribution)` accepts only `messages.upsert` envelopes with an instance and a nonblank `data.key.id`. Unsupported event kinds and entries without that stable message ID return no events (rejected; no unverified snapshot is created).
- The event carries source/distribution, message timestamp when it is an integral epoch-seconds value, conversation JID, sender JID, aliases, alias evidence, and tri-state `archived`.
- Group sender identity comes from `key.participant`; the group JID is kept as the conversation JID. Group events without participant do not use the group JID as a person.
- The mapper does not derive a phone number from a LID. It only emits a JID alias when the corresponding optional alternate JID field is explicitly present alongside the observed JID, and tags that pair `EXPLICIT_ALTERNATE_JID_PAIR`. `IdentityService` joins aliases only when this evidence is passed; otherwise it stores only the observed JID. Joins use the existing `identity_aliases` table in a transaction.
- `archived` is empty when absent or null; explicit `true` and `false` remain distinct. The boolean test payloads are synthetic and do not prove Evolution emits that field for message events.
- Synthetic fixture and provenance notes are in `src/test/resources/fixtures/evolution/README.md`. No Flyway change was needed; the existing alias schema is sufficient.

## RED → GREEN

RED command:

```powershell
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'
$env:TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE='/var/run/docker.sock'
mvn '-Dtest=EvolutionEventMapperTest,IdentityServiceTest' test
```

Before implementation, Maven failed at `testCompile` because `EvolutionEventMapper`, `IdentityService`, `IdentityObservation`, and `IdentityEvidence` did not exist. This was the expected RED. The first post-implementation run found one assertion comparing a `String` with `Optional<String>`; I corrected the assertion and reran.

Final focused verification:

```powershell
$env:DOCKER_HOST='npipe:////./pipe/dockerDesktopLinuxEngine'
$env:TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE='/var/run/docker.sock'
mvn '-Dtest=EvolutionEventMapperTest,IdentityServiceTest,EvolutionWebhookControllerHttpTest' test
```

Result: **15 tests, 0 failures, 0 errors, 0 skipped**. This includes 4 mapper tests, 3 PostgreSQL/Testcontainers identity tests, and 8 existing webhook HTTP compatibility tests. The full suite was not run. The output contains existing Java 25 Mockito dynamic-agent warnings from the webhook tests and a Testcontainers JUnit extension warning; they did not fail the run.

CodeGraph was queried before edits but returned symbols from the old `H:\Script\IA-hugo-do-zap` checkout. Per the required fallback, I used targeted `rg` and direct reads in this worktree instead of relying on that graph result.

## Upstream compatibility and archive reconciliation

This repository does not pin an Evolution release, commit, or image digest, so field compatibility with the future deployed build is not established. The mapper and fixture refer to the official [Evolution Baileys integration source](https://github.com/evolution-foundation/evolution-api/blob/main/src/api/integrations/channel/whatsapp/whatsapp.baileys.service.ts), which is the moving `main` branch. It declares optional alternate JID fields on `ExtendedIMessageKey` and wires a `messages.upsert` handler. It is not evidence that every release forwards those fields.

More critically, the current official source's `chats.update` handler forwards only `remoteJid` and `instanceId`; it drops the `archived` value. The mapper therefore rejects `chats.update` rather than fabricating a stable event ID or snapshot. There is no confirmed upstream transport or authoritative read endpoint in this worktree to reconcile archive state. Follow-up integration must validate one of these paths against a pinned official build: (1) an upstream event carrying explicit `archived` plus a stable ordering/cursor, or (2) a documented official read endpoint that returns current archive state and can be reconciled after startup/reconnect. Until such evidence exists, archive state remains UNKNOWN and archive-dependent processing must stay fail closed. If neither path is available, state will not converge and those chats cannot be safely processed.

## Self-review

- Confirmed existing controller construction remains source-compatible through the five-argument `EvolutionEvent` constructor; its focused HTTP tests pass.
- Confirmed no user data, production configuration, untracked `NotificationPolicy`, plans, or generated `target/` files were staged.
- No claim of real Evolution compatibility or production archive snapshots is made.
