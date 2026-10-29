# AI provider budget and retention

`BudgetService` accepts costs in USD micros (USD 0.000001 units). It serializes reservations and all accounting transitions under one PostgreSQL transaction lock, then checks daily and monthly limits in UTC. Both limits must exist and be positive before a reservation succeeds. Migration V20 intentionally inserts no limit row, so the default is blocked. V20 remains unchanged for checksum compatibility; V21 adds the `DISPATCHED` reservation state.

## Cost meanings

- **Estimated cost** is a conservative upper bound supplied before a paid request. The full estimate is reserved in both the UTC day and month buckets before the provider call.
- **Informed actual cost** is the provider-reported or operator-reconciled amount. Settlement replaces the reservation with this amount, including when it exceeds the estimate; later requests then see the higher spend.
- **Provider limit** is the external account's own quota, rate limit, or billing cap. This service cannot read or guarantee that limit. Provider rejection or timeout does not prove zero cost.

An actual cost above its estimate can push accounted spend past the configured budget after a call. The ledger records that amount and blocks later reservations, but it cannot provide a hard cap against provider billing.

`AiSimulationClassifier` uses `BudgetedProviderTransport` for every enabled provider HTTP attempt: reserve that attempt's conservative estimate, call `markDispatched` before the request can leave the process, then settle reported cost or mark an ambiguous result uncertain. Retries and provider fallbacks are separate paid attempts; each receives a fresh reservation. Provider requests run outside the decision transaction so rollback cannot erase spend. OpenRouter is primary and reported cost can settle its reservation; optional Gemini fallback gets an independent reservation and retains its conservative hold without cost evidence.

Only `RESERVED` can be released, so a dispatched or uncertain call cannot be discarded as zero cost. An ambiguous result stays `UNCERTAIN` and keeps its complete estimate counted. Reconcile it with `settle(actual)`, using zero only when evidence confirms no charge. A late settlement after `RELEASED` restores actual spend. Repeated settlement/release calls are safe. A settled cost is not erased by later release calls.

## Orphan reservations and ledger retention

`RESERVED`, `DISPATCHED`, or `UNCERTAIN` rows are never auto-released by age. A process can stop after sending a request but before recording its result. Treat stale `RESERVED` rows conservatively as potentially dispatched, confirm the outcome using the operator's existing billing records, then reconcile the matching reservation UUID with `settle`; use `release` only before dispatch. This ledger intentionally stores no provider or user identifier, so it cannot identify an external account or request on its own.

Budget ledger rows have no automatic deletion policy. They contain no prompts, message snippets, secrets, provider names, or user identifiers. Retain them so daily/monthly totals and later reconciliation remain auditable; do not purge them under the seven-day snippet-retention rule. Define a separate financial audit retention policy before adding ledger cleanup.

Metrics expose aggregate reservation outcomes without payload data.

Classification defaults OFF through `WATCHER_AI_ENABLED` and `WATCHER_AI_CLASSIFICATION_ENABLED`; the environment kill switch defaults ON. Migrations install no credentials, consent, or budget limits. By default attempts require current per-chat/provider/purpose consent and daily/monthly local limits; explicit environment opt-ins can dispense with either, as described in the activation checklist. Gates run again before each HTTP attempt and before decision persistence. Tests use fake transports and real disposable PostgreSQL; they do not prove remote billing, privacy or delivery. Operational activation still requires separate authorization and evidence.

Simulation does not automatically enqueue WhatsApp actions or individual intelligent alerts. The bridge currently drops numeric confidence and explicit urgency when converting provider results to `Classification`; it also lacks a persisted source-message reference tied to those results. Decision evidence fields therefore remain `UNCERTAIN`/zero/null, and alert authorization fails closed. Model evidence text cannot fill that gap. Connecting simulation directly to real enqueue would also need an explicit non-shadow execution boundary and atomic persistence of validated evidence plus enqueue. Flags alone are not proof of personal reference or permission to leave shadow mode. Reports remain aggregate-only.

Sensitive simulation snippets continue to use the existing maximum seven-day retention. Decision metadata follows its separate retention deadline and remains after snippet deletion.

V26 decouples the action evidence snapshot from the decision audit foreign key. A one-day audit deadline can erase decisions and batch data while an action keeps its original opaque decision UUID and its own bounded seven-day evidence. Missing decision blocks dispatch revalidation. Expired actions are deleted without a 1,000-row cap so an available backlog cannot retain bodies or roll back unrelated sensitive cleanup. Locked action rows are skipped until a later purge.
