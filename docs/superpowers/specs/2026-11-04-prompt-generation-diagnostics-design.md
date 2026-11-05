# Prompt generation diagnostics and delivery design

## Intent

Make `/prompt_generate` and `/prompt_refine` failures diagnosable per job and make the administrator's result delivery traceable from queueing through Telegram. The existing generic failure reply and swallowed exception leave operators unable to distinguish a local gate, provider failure, persistence problem, or delivery failure.

## Historical evidence gathered

- Job `56cbb7b2-3d36-42b8-8d56-29d81896998f` failed about 21 ms after claim. Its draft was unexpired at `started_at` and the current version matched the expected version. No budget reservation was found in its time window. The incident configuration showed local budget mode disabled, which matches the current preflight gate and identifies `BUDGET_MODE_BLOCKED`; the request did not reach budget reservation/provider transport.
- Job `c47a3a9b-85fb-4910-a922-452e9871239b` also had an unexpired draft and matching version. A 10,000-micro reservation was created 27 ms after claim and became `UNCERTAIN` about 444 ms after claim; the job became `FAILED` about 447 ms after claim. The current transport marks a reservation dispatched before calling the HTTP delegate, and marks it uncertain both for transport exceptions and for responses without confirmed cost; the reservation therefore does not prove that HTTP was invoked or that a response arrived. Its exact failure reason remains unconfirmed.
- Prometheus exposes only `job`, `instance`, and `outcome` labels for the budget counters, so the counters cannot associate a reservation with a job. Loki had no entries for either supplied interval.
- No matching outbox rows were returned by the later read-only query. This does not prove an action was never enqueued: successful acknowledgement expires its sensitive row immediately, and the retention job deletes expired outbox rows. The second generic failure reply was observed in Telegram; the first result remains unconfirmed. Persisting a payload-free delivery summary on the job addresses this evidence gap going forward.

## Goals

- Persist a stable, sanitized failure stage and reason code for terminal `FAILED` and `UNKNOWN` jobs; successful jobs have no failure reason.
- Persist a payload-free summary of the result action's status, attempts, sanitized last error, RabbitMQ confirmation, and expiry on the job, so the delivery history remains queryable after outbox retention removes the payload/action row.
- Correlate job lifecycle and provider-attempt logs by `jobId`, while excluding prompt instructions, generated prompts, chat payloads, credentials, exception messages, and provider request/response bodies.
- Export bounded-cardinality counters and latency histograms for enqueue, claim, terminal status, provider transport, outbox/RabbitMQ, and Telegram delivery. Identifiers never become metric labels.
- Tell the administrator when local policy, draft state, provider, validation, or persistence prevents a proposal, using a safe actionable explanation where possible.
- Preserve the transactional job-result outbox, transport retry policy, budget reservation semantics, and the rule against repeating a paid request after `DISPATCHED` or an uncertain outcome.
- Keep proposals as drafts; never activate them or enable WhatsApp sends.
- Record a successful prompt-generation audit outcome as `PROPOSED`, distinct from `APPLIED`, because generating an editable draft does not activate it.

## Non-goals

- No production deployment, environment-flag changes, paid reproduction request, manual job replay, or live Telegram/WhatsApp send.
- No attempt to reconstruct historical provider or delivery facts that were not retained.
- No logging or retaining prompt text, provider bodies, or Telegram payloads for diagnostics.

## Constraints

- Use an additive Flyway migration for any new job diagnostic columns.
- Extend the audit outcome constraint only through a forward migration so successful draft generation can be recorded as `PROPOSED` without claiming activation.
- Keep provider calls outside database transactions and never release a reservation after its dispatch boundary.
- Keep the job status update and result outbox enqueue atomic. If outbox admission fails, leave the job recoverable without rerunning generation.
- Mirror result-action transitions into a small, payload-free job summary in the same database transactions as the outbox transition; classify retention expiry without erasing an already acknowledged delivery result.
- Store and log only stable reason codes and stages. Do not log `exception.getMessage()` or raw exception bodies.
- Keep Prometheus labels to finite values such as operation, status, stage, and reason code; keep UUID correlation in application logs only.
- Continue using the existing Telegram admin reply transport and safe outbox retry behavior.

## Approaches considered

1. **Persist a per-job reason and instrument the existing end-to-end path (chosen).** Add typed stage/reason classification, a forward-only migration for the job's sanitized diagnostic, and lifecycle/provider/outbox/Telegram telemetry. This makes each job queryable even when log ingestion is unavailable and preserves the existing durable delivery route.
2. **Add logs and metrics only.** This is smaller, but a Loki gap leaves a terminal job without a queryable reason and does not satisfy per-job diagnosis.
3. **Create a separate prompt-result delivery pipeline.** This would isolate the feature but duplicates the Telegram admin outbox and introduces another retry/deduplication path, increasing the risk of duplicate or lost replies.

The user selected approach 1. The existing outbox remains authoritative while its action exists (`action_key`, status, attempts, RabbitMQ confirmation, expiry, and sanitized `last_error`); the job's payload-free result-delivery fields preserve a durable summary after outbox retention removes that row.

## Design

Represent failures using a finite `failure_stage` and `failure_code`, rather than exception text. Classify failures at the point with enough information to distinguish them: local preflight (including budget mode/exhaustion and draft expiry/version conflict), provider request setup/transport/HTTP response, output validation, proposal persistence, job claim, and result outbox admission. Record the precise transport invocation separately from the earlier budget `DISPATCHED` transition; a returned HTTP status proves a response was received, while a transport exception remains uncertain and must not trigger another paid attempt. Persist no failure fields for successful jobs.

Pass the job UUID only as a correlation field through the generation call and its safe lifecycle logs. Include the operation, stage, finite result code, elapsed time, and reservation UUID where useful. Persist `failure_stage` and `failure_code` on `prompt_generation_jobs`; do not store exception text. Add payload-free job fields `result_delivery_status`, `result_delivery_attempts`, `result_delivery_last_error`, `result_delivery_rabbit_confirmed_at`, and `result_delivery_expires_at`. Initialize delivery status to `NOT_ENQUEUED`; mirror `PENDING`, `PROCESSING`, `ACKNOWLEDGED`, `CANCELLED`, or `EXPIRED` from the matching result action. A retry remains `PENDING` and stores only the outbox's sanitized error code. Update the summary transactionally for result outbox enqueue, RabbitMQ confirmation, claim, acknowledgement, retry, and expiry. Keep the outbox payload and its existing short retention unchanged. Log and measure the existing action key and bounded state transitions without reading or emitting the payload. Count `prompt-generation-result:<jobId>` as an administrative Telegram delivery in the RabbitMQ metrics path.

Map failure codes to short, safe administrator replies. Keep the existing draft-preservation guarantee and activation confirmation flow. A delivery failure remains in the current outbox retry/DLQ behavior, with explicit delivery telemetry; it must not cause generation to run again. An interrupted dispatched job remains `UNKNOWN`, with an explicit reason and the existing no-retry notice.

The existing finish transaction remains atomic: it changes the job to a terminal state and admits its result action together. If database admission fails, both writes roll back and the job stays `DISPATCHED`; the scheduled stale-job scan later marks it `UNKNOWN` and retries only the safe uncertainty notice. Generation is never rerun. If generation had already persisted a proposal, the proposal remains an editable draft. An outbox action that was admitted is retried by its existing publisher/consumer flow. `UNKNOWN` remains terminal and is not reconciled to `COMPLETE` or retried against the paid provider.

## Verification expectations

Add regression coverage for the disabled-local-budget preflight, expired and concurrently changed drafts, provider setup/transport/HTTP failure, invalid provider output, persistence failure, atomic outbox failure and stale-job recovery without provider replay, durable `UNKNOWN` status, result-delivery summary transitions and expiry, low-cardinality metrics, sensitive-data exclusion, and Telegram delivery retry/administrative classification. Use fake provider and Telegram transports. Do not invoke real providers or messaging APIs. Run focused tests during implementation and the repository's `mvn -B -ntp verify` gate before preparing the PR.

## Builder decisions

- Exact finite stage/reason-code names and the short Telegram wording remain implementation details, provided they distinguish the failure classes listed above and never include raw error text.
- The migration remains additive and forward-compatible; no existing applied migration is edited.
- The supplied read-only rows describe the historical incidents; no further production access is required to implement the change, and the rows do not authorize flag changes or job replay.

The current outbox retention deletes rows once their sensitive expiry is reached (and acknowledgement sets that expiry to the acknowledgement time). The job summary therefore preserves only safe delivery metadata; it does not extend payload retention.
