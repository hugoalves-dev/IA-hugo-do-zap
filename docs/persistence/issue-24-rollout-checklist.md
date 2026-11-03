# Issue #24: production acceptance and rollback checklist

Status: **NOT APPROVED FOR DEPLOYMENT**. This document is a plan only. No environment, feature flag, credential, database or running service was changed by this PR.

## Before marking PR #28 ready

1. Require an independent review of SQL relocated from services, especially contact canonicalization, shadow capture, decision authorization, WhatsApp dispatch, Telegram outbox acknowledgements and native claim/lease semantics.
2. Run the full Java 25 `mvn -B -ntp verify` and the `Test and build (Java 25)` GitHub Actions check on the **latest commit**. Run PostgreSQL 17 Testcontainers with competing workers, duplicate webhooks, crash/restart, expired leases, failed provider calls, unknown WhatsApp outcomes and stale ACKs.
3. Verify GitHub Actions checks are green, plus persistence boundary tests and the database migration tests. Review that `Flyway` remains the sole schema owner; do not introduce schema auto-generation.
4. Confirm there are no data-destructive DDL changes in this PR. Capture a verified PostgreSQL backup and record the previous deployed immutable GHCR digest before any separately approved production rollout.
5. Obtain explicit approval before merging into `main`, because `.github/workflows/backend.yml` publishes an immutable image and automatically deploys on a qualifying push to `main`; pull-request runs only build/test.
6. Leave real-reply and real-alert feature flags in their existing states. Never enable sends to validate a persistence refactor.

## Deploy observation (only after approval)

- Validate `/actuator/health/readiness` and the healthchecks in `compose.yaml`, and observe metrics through the existing Prometheus endpoint.
- Compare rates/counts for accepted inbox events, lease claims/completions, pending/processing outbox, retries, stale tokens, classification decisions and data-retention jobs. Investigate any duplicate dispatch or growing backlog without exposing message payloads or secrets.
- Test policy/kill-switch enforcement, Telegram commands, archive filtering and simulated decisions. Fail closed when authorization, consent or evidence is missing.
- Use a fixed observation window approved by the operator. Do not declare success from a green liveness check alone.

## Rollback and safe restoration

- The current `scripts/deploy-vps.sh` automatically restores the previous Compose configuration if the new watcher fails the health check **and** the previous container was healthy. It does not guarantee automatic rollback after later functional regressions.
- For a regression detected after initial healthy startup, stop further promotion, retain the existing PostgreSQL data and volumes, and restore the previously recorded immutable image digest using the project's approved deployment procedure. Recheck readiness and critical queue/lease behavior before allowing normal operation.
- Do not blindly reverse Flyway migrations or overwrite production database state. If a new schema migration exists, require a separate, reviewed, forward-compatible recovery plan.
- Record the incident, old/new image digests, effect on queue leases or outbox attempts, and the decision to resume. Unknown WhatsApp outcomes must **never** be retried automatically during recovery.

**Gate:** keep PR #28 Draft and issue #24 open until an independent code review, current-head CI, integration evidence, backup and rollback preparations all pass.
