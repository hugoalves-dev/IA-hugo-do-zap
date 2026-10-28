# Task 5 report — consented AI simulation and local acceptance

Date: 2026-10-27

## Delivered behavior

- Connected `SimulationService` to `ClassificationService` through `AiSimulationClassifier`, gated by `watcher.ai.enabled=false` by default, persisted AI enablement, kill switch, archive scope, provider configuration, explicit data consent and budget. No provider credentials, limits or consent records are installed by migrations.
- V25 adds expiring privacy approval per chat/situation/provider and a durable per-batch AI claim. Direct chats require `CONTACT_REQUEST`; groups require `GROUP_TRIGGER` and an exact structured mention of the configured owner. `CRITICAL_URGENCY` is an additional purpose only with explicit consent and objective mention. No model-produced reference, authorization, template or evidence can grant permission.
- Primary Gemini and optional OpenRouter fallback are created with `BudgetedProviderTransport`. Every HTTP attempt, including Gemini schema retry and technical fallback, rechecks consent/settings, reserves its own conservative hold, and commits `DISPATCHED` before transport invocation. Missing either limit blocks before HTTP. OpenRouter retains strict ZDR/no-data-collection/no-provider-fallback routing.
- Accounting transactions use `REQUIRES_NEW`; a subsequent decision rollback cannot erase a dispatched reservation. A valid explicit monetary OpenRouter usage cost settles in USD micros rounded upward. Gemini token usage is not confirmed monetary billing: successful Gemini calls retain `UNCERTAIN` holds. Missing cost, timeout, HTTP failure and ambiguous outcomes also retain full holds. Nothing automatically releases dispatched/uncertain spend.
- No SQL transaction or advisory lock spans provider HTTP. AI claims and results commit independently; final decision uses a short transaction and rechecks archive scope, flags, consent and policy. Result publication checks the lease token and deadline. A concurrent active claim blocks duplicate paid requests; expired/crashed claims never automatically redispatch. A result saved before decision failure is reused without another paid call.
- Cached metadata records only bounded classification fields, the actual provider and original approved purposes. It stores no prompt, message text or model evidence. Consent revocation invalidates cached results, including OpenRouter fallback results. AI metadata is deleted with its batch by FK cascade.
- Simulation does not enqueue WhatsApp actions or individual Telegram alerts. A real positive simulated reply/alert is tested with an explicitly approved template. Fake Telegram receives an explicitly scheduled aggregate-only daily report; the disabled Evolution dispatcher makes zero calls.
- Added `docs/local-ai-activation-checklist.md` covering local acceptance, cost reconciliation, private routes, retention, explicit authorizations, Evolution event/REST parity, rollback and separate operational approvals. Screenshots are expressly insufficient evidence.

## RED / GREEN evidence

- RED: new transport tests failed compilation because `BudgetedProviderTransport` did not exist. New acceptance tests then failed compilation because the AI adapter/properties did not exist.
- RED: a regression reproduced cached results remaining available after an original approved purpose was revoked. The PostgreSQL assertion failed (`expected false but was true`). The fix records original purposes/provider and revalidates the same authorization on cache use and final persistence.
- An added positive decision assertion initially failed because V17 intentionally disables the original undisclosed seed templates. The test fixture now explicitly approves a template with the required `Resposta automática:` prefix; production template rules remain unchanged.
- GREEN: isolated scratch `H:\Script\IA-hugo-do-zap-evolution-poc-task5-scratch`, JDK 25, PostgreSQL 17 Testcontainers. Focused command:

  ```powershell
  mvn '-Dtest=BudgetedProviderTransportTest,AiSimulationAcceptanceTest,BudgetServiceTest,BudgetMigrationUpgradeTest,ClassificationServiceTest,GeminiProviderTest,OpenRouterProviderTest,SimulationServiceTest,SimulationSchedulerTest,SpringFlywayStartupTest' '-Dapi.version=1.44' test -q
  ```

  Result: 59 tests, 0 failures, 0 errors, 0 skipped. After adding three recovery/gate acceptance cases, the focused `mvn '-Dtest=AiSimulationAcceptanceTest' '-Dapi.version=1.44' test -q` passed the final 17-case acceptance class separately: 17 tests, 0 failures, 0 errors, 0 skipped. Together these final directed runs cover 62 distinct test cases (the 14 previous acceptance cases overlap).
- Coverage includes absent credentials/limits/consent, unapproved privacy, monthly limit, concurrent budget attempts, concurrent batch claims, schema retry, technical fallback, fallback privacy-route failure, revocation between attempts and on cache, kill switch, unknown archived state, expired claims, provider calls outside SQL transactions, ledger survival across rollback, positive simulated action without real enqueue, Telegram aggregate and Evolution OFF.
- `BudgetMigrationUpgradeTest` is now explicitly pinned to target V21: it tests V20-to-V21 checksum/data compatibility, rather than assuming V21 remains the latest migration. Startup independently verifies V25 and no default limits/consents.
- CodeGraph returned an unindexed workspace (0 nodes); targeted source inspection traced providers, internal retry, transport, budget lifecycle, decision policy, simulation, report/outbox and disabled dispatch. RTK instruction file at the requested path is absent; native targeted commands were used.
- No full suite was run. No files from checkout `target/` or `.idea/` were read, staged or committed. Test/build output exists only in scratch. No real Gemini, OpenRouter, Telegram or Evolution requests, paid account configuration, pairing or VPS changes were performed.

## Flags and remaining operational limits

- New property `watcher.ai.enabled` defaults OFF. SQL AI/alert/auto-reply defaults remain OFF and kill switch defaults ON. Telegram individual alerts and WhatsApp real-send properties remain OFF; actual Evolution transport remains disabled. Only disposable test databases used enabled fixtures.
- Tests prove local behavior with fakes and real PostgreSQL, not remote privacy/billing/delivery or production readiness. Real activation requires explicit operational authorization and the checklist's independent evidence.
- Local budget limits serialize reservations, not external provider billing. Conservative hold calibration and provider account limits remain operator responsibilities. A reported actual charge can exceed its hold; it is recorded and blocks subsequent reservations. Gemini holds intentionally consume capacity until evidence-based reconciliation.
- Gates are rechecked before each HTTP without holding a SQL lock during network work. A revocation after the last gate cannot recall an already dispatched request. No instantaneous global cancellation or rigid provider-billing ceiling is claimed.
- Crash recovery favors privacy/cost containment over classification availability: an interrupted or expired claim may yield unavailable classification permanently for that batch, while spend stays reserved. Financial holds must not be cleared merely to restore capacity.
- Structured personal/group references currently support exact owner mentions in `extendedTextMessage.contextInfo.mentionedJid`. Other formats conservatively provide no reference. No model explanation is retained or trusted for permission.
