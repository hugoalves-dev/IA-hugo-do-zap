# AI provider budget and retention

`BudgetService` accepts costs in USD micros (USD 0.000001 units). It serializes reservations against PostgreSQL daily and monthly limits in UTC. Both limits must exist and be positive before a reservation succeeds. Migration V20 intentionally inserts no limit row, so the default is blocked.

## Cost meanings

- **Estimated cost** is a conservative upper bound supplied before a paid request. The full estimate is reserved in both the UTC day and month buckets before the provider call.
- **Informed actual cost** is the provider-reported or operator-reconciled amount. Settlement replaces the reservation with this amount, including when it exceeds the estimate; later requests then see the higher spend.
- **Provider limit** is the external account's own quota, rate limit, or billing cap. This service cannot read or guarantee that limit. Provider rejection or timeout does not prove zero cost.

An ambiguous result stays `UNCERTAIN` and keeps its complete estimate counted. Reconciliation can settle it with an actual amount or release it only when an operator has evidence that no charge occurred. Repeated settlement/release calls are safe. A settled cost is not erased by later release calls.

Budget rows store only amounts, UTC periods, timestamps, UUID reservation keys, and state. They contain no prompt, provider name, secret, message, or user identifier. Metrics expose aggregate reservation outcomes without payload data.

Classification and simulation remain disconnected from paid providers. There is no configured data authorization, daily/monthly budget, provider credential, or application wiring that can reserve before every HTTP attempt and reconcile provider cost. `ai_classification_enabled` remains false by default. Do not enable classification until each provider attempt has a real data authorization, a guaranteed pre-call reservation, and a settlement policy; retries and fallback calls each need their own reservation. No live network call was made for this task.

Sensitive simulation snippets continue to use the existing maximum seven-day retention. Decision metadata follows its separate retention deadline and remains after snippet deletion.
