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
