# AI provider budget and retention

`BudgetService` accepts costs in USD micros (USD 0.000001 units). It serializes reservations and all accounting transitions under one PostgreSQL transaction lock, then checks daily and monthly limits in UTC. Both limits must exist and be positive before a reservation succeeds. Migration V20 intentionally inserts no limit row, so the default is blocked.

## Cost meanings

- **Estimated cost** is a conservative upper bound supplied before a paid request. The full estimate is reserved in both the UTC day and month buckets before the provider call.
- **Informed actual cost** is the provider-reported or operator-reconciled amount. Settlement replaces the reservation with this amount, including when it exceeds the estimate; later requests then see the higher spend.
- **Provider limit** is the external account's own quota, rate limit, or billing cap. This service cannot read or guarantee that limit. Provider rejection or timeout does not prove zero cost.

An actual cost above its estimate can push accounted spend past the configured budget after a call. The ledger records that amount and blocks later reservations, but it cannot provide a hard cap against provider billing.

Before a provider request can leave the process, mark its reservation `DISPATCHED`. Only `RESERVED` can be released, so a dispatched or uncertain call cannot be discarded as zero cost. An ambiguous result stays `UNCERTAIN` and keeps its complete estimate counted. Reconcile it with `settle(actual)`, using zero only when evidence confirms no charge. A late settlement after `RELEASED` restores actual spend. Repeated settlement/release calls are safe. A settled cost is not erased by later release calls.

## Orphan reservations and ledger retention

`RESERVED`, `DISPATCHED`, or `UNCERTAIN` rows are never auto-released by age. A process can stop after sending a request but before recording its result. Treat stale `RESERVED` rows conservatively as potentially dispatched, confirm the outcome using the operator's existing billing records, then reconcile the matching reservation UUID with `settle`; use `release` only before dispatch. This ledger intentionally stores no provider or user identifier, so it cannot identify an external account or request on its own.

Budget ledger rows have no automatic deletion policy. They contain no prompts, message snippets, secrets, provider names, or user identifiers. Retain them so daily/monthly totals and later reconciliation remain auditable; do not purge them under the seven-day snippet-retention rule. Define a separate financial audit retention policy before adding ledger cleanup.

Metrics expose aggregate reservation outcomes without payload data.

Classification and simulation remain disconnected from paid providers. There is no configured data authorization, daily/monthly budget, provider credential, or application wiring that can reserve before every HTTP attempt and reconcile provider cost. `ai_classification_enabled` remains false by default. Do not enable classification until each provider attempt has a real data authorization, a guaranteed pre-call reservation, and a settlement policy; retries and fallback calls each need their own reservation. No live network call was made for this task.

Sensitive simulation snippets continue to use the existing maximum seven-day retention. Decision metadata follows its separate retention deadline and remains after snippet deletion.
