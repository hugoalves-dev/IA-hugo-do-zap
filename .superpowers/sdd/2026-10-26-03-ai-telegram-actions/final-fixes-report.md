# Final review fixes — plan 3

## Changes

- Retention: V26 removes the action-to-decision audit FK. The independently bounded action snapshot retains its original opaque decision UUID until its own maximum seven-day deadline; expired decisions, batch metadata and snippets are deleted on their original deadlines. A missing decision rejects enqueue and dispatch revalidation. Action deletion no longer stops after 1,000 available rows, avoiding sensitive backlog and rollback of unrelated inbox cleanup. Locked rows still defer to a later purge.
- Template binding: authorization joins the exact `template_id@vN` reference persisted by DecisionEngine. It never substitutes the latest version. Existing synthetic fixtures now use the real reference format. Tests cover an engine decision, newer coexisting version, revalidation, revocation and a complete fake-provider/budget/classifier/SimulationService persistence flow followed by explicitly invoked local enqueue. No real delivery occurs.
- Telegram: invalid `/ferias` and `/feriado` dates are caught as DateTimeParseException, audited REJECTED and answered. Token consumption and polling offset commit, allowing a subsequent `/emergencia` to apply. No availability or holiday mutation occurs for the invalid dates.
- Updated `docs/ai-budget.md`: classification is connected to the consent/budget-gated provider bridge, defaults OFF, and local fakes do not prove remote integrations.

## Producer gap remains fail closed

No automatic simulation-to-action or simulation-to-individual-alert producer was enabled. `AiSimulationClassifier.convert/encode` currently discards numeric confidence and explicit provider urgency. `Classification` contains only situations and booleans; it cannot truthfully supply persisted confidence/status/urgency plus an exact source-message hash tied to the classification. The existing exact owner-mention structure provides some personal-reference evidence but does not restore the discarded result metadata or establish a distinct non-shadow execution boundary.

Decision alert evidence therefore remains `UNCERTAIN`/zero/null. IntelligentAlertService rejects it. Simulation continues to produce no individual alert or WhatsApp outbox entry automatically. A safe follow-up needs an explicit execution-mode boundary and a typed, revalidated metadata/source contract, persisted atomically with a gated enqueue. Do not fabricate confidence, hash model prose, or treat a model assertion as proof of personal reference. This is an unfinished plan requirement, not a completed production producer.

## Verification

Work ran from clean scratch `H:/Script/IA-hugo-do-zap-evolution-poc-final-fixes-scratch` with JDK `F:/graalvm-25+37-LTS-jvmci-b01`, fake provider/Telegram/Evolution transports and real PostgreSQL 17.11 Testcontainers. Existing worktree `target/`, `.idea/` and dirty progress file were preserved.

RED evidence in scratch:

- `red-template-dates.log`: 2 tests, 2 assertion failures. Exact version reference rejected enqueue; invalid date stalled offset at 1 instead of 4 with DateTimeParseException.
- `red-retention.log`: FK violation on `whatsapp_action_outbox_decision_id_fkey` when one-day decision audit expired before the seven-day action snapshot.
- `red-backlog.log`: after decoupling FK, a 1,002-action backlog retained 2 expired action bodies because of the old cap.
- Initial focused verification exposed a missing-decision idempotency acceptance; added `DECISION_NOT_FOUND` guard. Also corrected token test to account for intentional pruning of consumed tokens on issue; each confirmation is now checked immediately.

Focused command (no full suite):

```powershell
mvn '-Dtest=WhatsAppActionServiceTest,TelegramUpdatePollingServiceTest,AiSimulationAcceptanceTest,RetentionServiceTest,RetentionRegressionTest,SimulationServiceTest,SpringFlywayStartupTest' '-DargLine=-Dapi.version=1.44' test -q
```

Initial directed run: 51 tests, 2 failures, 0 errors, 0 skipped. The five unaffected classes passed 33 tests. After the two adjustments above, reran only `WhatsAppActionServiceTest,TelegramUpdatePollingServiceTest`: 18 tests, 0 failures, 0 errors, 0 skipped, Maven exit 0 (`green-actions-polling.log`). Combined directed evidence covers 51 distinct tests; the complete seven-class command was not repeated after that final small guard/test adjustment. The polling warning from its intentional failure/rollback test is expected. No remote integration or entire-suite success is claimed.

CodeGraph reported an unindexed workspace (0 nodes), so targeted source reads and rg traced the relevant services, decision/template contracts, callbacks, migrations and tests. Referenced RTK instruction path was absent; native commands were used. No agents, real provider requests, paid configuration, pairing, VPS changes, push, or external messages were performed.
