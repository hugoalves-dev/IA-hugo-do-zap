# Evolution webhook incident diagnostics (issue #60)

This runbook separates **WhatsApp upstream reception**, **Evolution webhook delivery**, **RabbitMQ publication/consumption**, **durable inbox processing**, **message eligibility**, **shadow decision** and **Telegram delivery**.

## Safety boundary

- Only the *exact* Docker endpoint `http://evolution-api:8080` may be used for HTTP by the optional real-action transport. External endpoints require HTTPS. Keep all real-send flags and the persistent kill switch in their current safe state.
- Never expose the Evolution API key, webhook bearer token, chat JID/LID, telephone, name, raw message, prompt or body in logs or metrics.
- Do not turn on verbose Evolution `LOG_LEVEL`/webhook payload logging to investigate a missing event.
- No replay of old live WhatsApp messages without independently proving freshness, authorization, idempotency and delivery safety.
- A zero webhook rate is **not** by itself a failure: there may be no new messages. Confirm with an independently observed incoming event before concluding data loss.

## Configuration validation

When all three real-action flags are explicitly enabled, `HttpEvolutionActionTransport` reports **only the invalid property name** in its startup exception. Check the runtime presence (not values) of:

- `WATCHER_WHATSAPP_ACTIONS_EVOLUTION_BASE_URL` (canonical Docker URL above)
- `WATCHER_WHATSAPP_ACTIONS_EVOLUTION_INSTANCE`
- `WATCHER_WHATSAPP_ACTIONS_EVOLUTION_API_KEY`
- `WATCHER_WHATSAPP_ACTIONS_EVOLUTION_TIMEOUT` (positive, no more than 2 minutes)

A valid startup is not evidence that WhatsApp incoming event delivery works.

## Prometheus / Grafana Explore

Use these expressions with the desired incident interval (all counters restart when the watcher restarts):

```promql
sum by (stage, outcome) (increase(watcher_evolution_pipeline_events_total[15m]))
```

```promql
sum by (uri, status) (increase(http_server_requests_seconds_count{job="ia-hugo-do-zap",method="POST",uri="/internal/evolution/webhook"}[15m]))
```

```promql
time() - watcher_evolution_last_upsert_received_epoch_seconds
```

The last expression is meaningful only after at least one authenticated `messages.upsert` event since process start; initial value is 0.

Stage/outcome combinations:
- `webhook/received`: authenticated, parseable message upsert with a technical event ID; if absent, check Evolution event production, its webhook subscription and HTTP reachability.
- `rabbit/published` or `rabbit/failed`: producer-confirmed queue routing, or publication failure; HTTP 503 asks Evolution to retry.
- `inbox/accepted` or `inbox/duplicate`: durable ingest admission or idempotent duplicate, after the Rabbit delivery reached the consumer.
- `rabbit/consumed`, `rabbit/retry`, `rabbit/dead_letter`: Rabbit consumer ACK/retry/DLQ outcomes; reconcile with RabbitMQ queue depths.
- `job/completed`, `job/ignored`, `job/duplicate`, `job/quarantined`, `job/rejected`: durable processor outcomes **after the transaction**, including message-service eligibility.
- `telegram/sent`, `telegram/suppressed`, `telegram/retry`: archived-message notification outbox delivery only. This is **not** a metric of simulated intelligent alerts or unsolicited WhatsApp replies.

Existing `watcher_shadow_*` metrics and `shadow_decision` audit logs distinguish classification, triage, `NO_ACTION` and policy blockers. A `NO_ACTION` for one group cannot explain a different group's undelivered message.

## Opaque correlation

Temporarily enable DEBUG for `dev.hugo.watcher.EvolutionPipelineMetrics` only when authorized. Logs use a truncated SHA-256 digest of an opaque technical event ID, not the raw message ID or payload. Counters never use per-message labels; `stage` and `outcome` are fixed Java enums.

Rabbit delivery may be retried after ambiguous failures. Correlate events using transaction timestamps, decision IDs and the persisted inbox rather than assuming exactly-once delivery or a one-to-one ratio between stage counts.

## Incident 2026-11-05

At about 16:59 BRT, a message was visible in the group `Operação Código de Ouro - Alunos`, but no matching persisted watcher inbox event was found. The watcher had previously failed during transport configuration (16:04–16:06) and was healthy from 16:07:44; the most recent persisted event was at 16:47:18. This evidence does **not** prove whether Evolution emitted the missing webhook.

This change is **observability and actionable startup validation**, not a reconstruction of missing messages or a verified cure for upstream Evolution delivery. Validate a future naturally occurring inbound message end to end before declaring the incident closed.
