# Durable decision producer completion

## Delivered behavior

`SimulationService` now persists normalized classification urgency, confidence, status and a source-message SHA-256 reference alongside its audited decision. `AiSimulationClassifier` preserves those fields through its durable result and revalidation. Personal reference is derived only from persisted, unexpired incoming non-history message context: exact configured owner mention, or reply stanza referring to an independently indexed authenticated owner message. Model evidence/prose and caller-provided in-memory mentions cannot establish proof. Model evidence text is never added to the audit or delivery payload.

`DecisionActionProducer` runs inside the decision transaction and defaults OFF via `watcher.decision-producer.real-mode-enabled=false`. In shadow, neither enqueue service is called. In explicitly enabled real mode, only a persisted valid/confident, eligible audit can produce Telegram intelligent-alert and approved-template WhatsApp work. Both channels can coexist for one decision. Existing per-channel flags remain OFF by default. SQL failure in either enqueue rolls back the audit, both queues and tombstones; the durable model result allows recovery without another model call.

V27 adds `production_mode` (historical/default FALSE) and the audited `alert_template_reference`. A shadow audit cannot be promoted by restarting/replaying with the real flag; a newer alert template approval cannot replace the version captured by the decision. Existing migrations were not edited.

Both enqueue and dispatch gates now require durable model provenance and current consent by chat, provider and purpose, audit/source expiry, archive scope, current schedule availability, structured reference where required, cooldown, kill switch, feature flags and explicit chat/situation/template authorization. Alerts retain the critical personally referenced exception during unavailability; automatic replies retain the existing approved-template policy for returning later. Old queued actions lacking these provenance/mode fields fail closed.

## Evidence

CodeGraph symbol lookup returned an empty workspace (0 indexed nodes); used targeted direct reads and rg immediately. No repository-wide scan or generated/dependency directory inspection was performed.

Scratch only: `H:/Script/IA-hugo-do-zap-evolution-poc-producer-scratch`, copied `src/` and `pom.xml`; JDK `F:/graalvm-25+37-LTS-jvmci-b01`, PostgreSQL 17.11 Testcontainers, fake model transport. Original worktree `target/`, `.idea/`, and existing dirty progress file were preserved.

RED observed:

- Producer acceptance tests could not compile because `DecisionActionProducer` did not exist (after correcting an initial new-test encoding error).
- Pending produced action still returned `AuthorizedAction` after data consent revocation; expected null. Shared runtime privacy/scope gate fixed it.
- Replaying a shadow audit after enabling real producer created 1 action; expected 0. Persisted decision mode fixed it.

GREEN, exit 0:

```text
mvn -Dtest=AiSimulationAcceptanceTest,WhatsAppActionServiceTest,IntelligentAlertServiceTest,SimulationServiceTest,DecisionEngineTest,SpringFlywayStartupTest -DargLine=-Dapi.version=1.44 test -q
```

74 tests, 0 failures/errors/skips: acceptance 29, WhatsApp actions 13, intelligent alerts 6, decision engine 15, simulation 10, Spring/Flyway startup 1. Spring startup applied/validated V27.

Final directed additions/strengthening:

```text
mvn -Dtest=AiSimulationAcceptanceTest#pendingHighAlertRechecksCurrentAvailabilityAndEvidenceExpiry -DargLine=-Dapi.version=1.44 test -q
mvn -Dtest=WhatsAppActionServiceTest#confirmedDeliveryAdvancesRealCooldown -DargLine=-Dapi.version=1.44 test -q
mvn -Dtest=AiSimulationAcceptanceTest#fabricatedPersonalReferenceInModelEvidenceNeverAuthorizesCriticalUrgency -DargLine=-Dapi.version=1.44 test -q
```

Each final directed test passed: the availability/expiry case adds one distinct case; the other two revalidate strengthened existing cases. Final coverage: 75 distinct focused cases, no failures/errors/skips. Tests cover valid fake model to audited decision to both durable queues, no flags/consent/group trigger, true shadow and non-promotion, independently indexed owner reply, forged model evidence/in-memory mention, runtime revocation/scope/kill switch/template version, concurrent duplicate/restart, transaction rollback/recovery without another model call, and current availability/source expiry. JDK/Testcontainers/Mockito emitted their existing deprecation/agent warnings; no application test failures.

## Remaining limits

No full suite, paid provider call, real Telegram/Evolution delivery, deploy, account activation, pairing or VPS change was performed. Evolution action transport remains `DisabledEvolutionActionTransport`. Provider classifications remain opt-in with separate data consent and budget gates even during shadow analysis. Runtime revocation cannot recall an HTTP request already dispatched after its last gate. A crash after remote send retains the existing ambiguous/unknown delivery semantics. Parent reviews and pushes this commit.


## P2 review follow-up: confirmed cooldown across sibling channels

Reproduced with real PostgreSQL and fake transports: WhatsApp delivery first suppressed its sibling Telegram alert; Telegram delivery first left the real cooldown null. V28 adds `decision_cooldowns.last_real_decision_id`. Cooldown remains shared by conversation, while the originating decision's sibling channels bypass only cooldown, retaining every current privacy/scope/reference/template/kill-switch gate. Future decisions stay blocked for the configured interval.

Telegram's successful lease-token ACK now updates outbox state and real cooldown in one transaction, using the stored immutable action key. WhatsApp's confirmed `DELIVERED` transition records the same cause in its existing transaction. Both use monotonic timestamp updates; duplicate/stale ACKs cannot advance cooldown again. No real cooldown update occurs for simulation, suppression, confirmed failure or ambiguous outcome. A later sibling confirmation extends the shared window.

RED PostgreSQL command selected the two delivery orders, duplicate ACK concurrency and shadow/ambiguous outcomes. The two order cases failed as expected: WhatsApp→Telegram sent 0 (expected 1), and Telegram→WhatsApp left last_real_action_at null. The initial concurrent case also exposed a test rate-limit fixture dependency, corrected by resetting the shared delivery rate-limit row in setup.

Focused regression command:

```text
mvn -Dtest=AiSimulationAcceptanceTest,WhatsAppActionServiceTest,IntelligentAlertServiceTest,NotificationServiceTest,SpringFlywayStartupTest -DargLine=-Dapi.version=1.44 test -q
```

89 cases ran: 87 passed; two final assertions required fixture corrections. The concurrent ACK assertion compared nanosecond Java time against PostgreSQL microsecond precision; input now truncates to micros. `NotificationServiceTest.revalidatesIntelligentAlertAfterEnqueueAndSuppressesRevokedAuthorization` had an old synthetic audit without production mode, template binding or durable model/consent provenance; fixture now satisfies those gates before testing revocation. Production gates were not weakened.

Final directed verification, exit 0, 6 tests, 0 failures/errors/skips:

```text
mvn -Dtest=AiSimulationAcceptanceTest#whatsappThenTelegramConfirmsBothSiblingsAndBlocksFutureDecision+telegramThenWhatsappConfirmsBothSiblingsAndStartsRealCooldown+concurrentDuplicateTelegramAcknowledgementAdvancesCooldownOnce+ambiguousDeliveryAndShadowNeverStartRealCooldown+telegramAckAndCooldownRollbackTogetherAndRetryOnlyAcknowledgesOnce,NotificationServiceTest#revalidatesIntelligentAlertAfterEnqueueAndSuppressesRevokedAuthorization -DargLine=-Dapi.version=1.44 test -q
```

The final tests exercise both delivery orders with nonzero cooldown, blocked future decisions, concurrent duplicate ACKs and stale retry, thrown fake WhatsApp timeout (`UNKNOWN`) plus Telegram failure, shadow without real cooldown, and a SQL-trigger failure proving ACK and cooldown roll back together before idempotent recovery. Combined follow-up coverage comprises 90 distinct cases with final outcomes passing (87 unchanged passing cases plus rerun corrections and the new rollback case); no full suite rerun. Spring startup applied and validated V28. Defaults remain OFF, real Evolution transport remains Disabled, no external request or deployment performed.


## Final P2 follow-up: durable dispatch admission across workers

Reproduced the remaining race with two PostgreSQL-backed workers and a blocked fake Telegram HTTP call: both different decisions for one chat sent before the first confirmation (2 sends, expected 1). V29 introduces a durable conversation claim keyed by SHA-256 of the chat JID, plus per-decision/channel attempt tokens and states. Dispatch admission commits before HTTP and never keeps a database transaction open across transport. A different decision waits while the owning decision has `DISPATCHED` or `UNKNOWN` work. Sibling channels of the same decision remain eligible subject to every existing policy gate. Busy work is deferred instead of incorrectly acknowledged/suppressed.

Telegram uses its persisted outbox lease token; WhatsApp uses its immutable action UUID. Matching confirmed ACK/DELIVERED transitions settle the attempt atomically with existing state and cooldown. Explicit Telegram rejection records `RETRYABLE` with queue retry atomically; transport ambiguity records `UNKNOWN`. WhatsApp confirmed failure and ambiguity settle with its status transaction. Neither timeout, crash nor elapsed lease grants another send. A lease-expiry RED additionally showed that a second worker could replace the still-running Telegram worker's lease and lose its eventual positive ACK. `claimNext` now excludes lease takeover for already-dispatched/unknown intelligent alerts, with an indexed lookup; the original token can acknowledge safely even after its lease time passes.

Transport source tracing found `TelegramBotApiTransport` previously collapsed exception/null responses into a confirmed failure. `TelegramDeliveryResult` now carries an explicit ambiguity flag while preserving its three-argument constructor. Exceptions, absent responses and server failures/invalid error codes return UNKNOWN. Explicit API rejection/rate limiting remains distinct. This ambiguity rule applies to the new intelligent-alert path; legacy notification retry semantics are unchanged.

RED evidence: (1) two-worker slow HTTP sent 2 rather than 1; (2) exception/null/500 adapter results equaled confirmed rejection; (3) lease takeover lost ACK (expected acknowledged/confirmed count 1). Each was observed before its correction.

GREEN in the same isolated JDK25/PostgreSQL17.11 scratch, all commands exit 0:

```text
mvn -Dtest=AiSimulationAcceptanceTest,WhatsAppActionServiceTest,NotificationServiceTest,SpringFlywayStartupTest -DargLine=-Dapi.version=1.44 test -q
```

85 cases, 0 failures/errors/skips (acceptance36, WhatsApp13, notifications35, startup1). This included the initial dispatch reservation, both sibling delivery orders, existing concurrent WhatsApp dispatch and legacy notifications. Spring startup applied/validated V29.

```text
mvn -Dtest=AiSimulationAcceptanceTest#crashedTelegramDispatchLeaseNeverReissuesAndStillAllowsSiblingChannel+confirmedTelegramRejectionAllowsSafeRetryButUnknownNeverDoes+telegramTransportTimeoutRetainsUnknownAcrossWorkerRestart+twoWorkersCannotDispatchDifferentDecisionsDuringSlowTelegramRequest+whatsappThenTelegramConfirmsBothSiblingsAndBlocksFutureDecision+telegramThenWhatsappConfirmsBothSiblingsAndStartsRealCooldown,TelegramBotApiTransportTest -DargLine=-Dapi.version=1.44 test -q
```

10 cases passed: 6 PostgreSQL dispatch/recovery/order cases plus 4 adapter cases. After adding the server-error case, the final `mvn -Dtest=TelegramBotApiTransportTest test -q` passed 5 adapter cases. After strengthening the HTTP test to expire the active lease, the final command selecting `twoWorkersCannotDispatchDifferentDecisionsDuringSlowTelegramRequest`, `crashedTelegramDispatchLeaseNeverReissuesAndStillAllowsSiblingChannel`, `confirmedTelegramRejectionAllowsSafeRetryButUnknownNeverDoes`, and `telegramTransportTimeoutRetainsUnknownAcrossWorkerRestart` passed all 4 PostgreSQL cases. Together these overlapping runs cover 93 distinct focused cases, all final outcomes passing; no full suite run.

The slow transport asserts no SQL transaction is active. Tests prove another decision is deferred, original HTTP ACK survives lease expiry, crashed lease and worker restart never reissue a dispatched/unknown request, sibling WhatsApp still confirms, unknown does not start cooldown, and independently confirmed rejection allows retry. Tombstones survive payload/source expiry because they have no cascading FK. They contain only hashed chat key, UUIDs, channel/state and timestamp, never content or recipient address.

Operational limit: DISPATCHED/UNKNOWN tombstones intentionally do not expire into permission to send, even after their sensitive queue payload is purged. A truly ambiguous conversation can remain blocked pending independent remote evidence; an evidence-backed reconciliation workflow and real Evolution transport are still not implemented. Never delete these tombstones merely to restore sending capacity. Defaults stay OFF. Evolution remains Disabled. No real transport request, deploy, pairing or account configuration occurred.
