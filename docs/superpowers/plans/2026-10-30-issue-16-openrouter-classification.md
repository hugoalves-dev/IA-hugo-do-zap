# Issue #16: OpenRouter classification reliability and latency plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make OpenRouter output budget and reasoning controllable from Compose/env; fail closed on truncation; measure where classification time is spent without exposing private content.

**Architecture:** Preserve the existing Spring Boot 4/Java 25 single-process watcher and its PostgreSQL leases, consent, paid-budget transport and Shadow Mode. Add explicit provider payload controls and bounded Micrometer metrics, then use live evidence before considering concurrent dispatch.

**Tech Stack:** Spring Boot 4.0.9, Java 25, OpenRouter/Haiku 5.5, Micrometer/Prometheus, Docker Compose.

**Spec:** https://github.com/hugoalves-dev/IA-hugo-do-zap/issues/16

## Global constraints

- No production .env write, push to main, deploy, WhatsApp sending, or real actions.
- Keep ZDR, data_collection=deny, allow_fallbacks=false, require_parameters=true, budget, per-chat consent and kill switch.
- No prompts, chats, phones, JIDs, secrets or batch IDs as metric labels.
- Preserve existing serial, durable batch claims until backlog measurement supports a concurrency change.

## Review focus

- Missing env keys default to 300 and reasoning=false; invalid numeric values do not enable classification.
- Explicit reasoning false/true must both reach the request alongside strict routing parameters.
- finish_reason=length or absent content fails closed even if JSON is parseable.
- Classification errors stay separated from transport timeouts; no technical fallback for truncated output.
- Timer histograms and finish_reason metrics may only carry bounded labels.

## Tasks

### 1. Configuration and payload
- [x] Write failing tests for env binding and reasoning on/off.
- [x] Add typed property defaults/range checks, Compose plumbing, explicit request reasoning and docs.
- [ ] Verify with Java 25 CI and local Compose config with placeholder credentials.

### 2. Structured response integrity
- [x] Write regression tests for finish_reason=length, reasoning-only output and unsupported finish reasons.
- [x] Reject truncation before JSON parsing; preserve strict-schema and fail-closed behavior.
- [ ] Run full Maven test suite, including budget/privacy regressions.

### 3. Bounded observability
- [x] Add tests for reasoning usage, truncation and bounded finish labels.
- [x] Add provider/schema failure counters, classification/queue/decision/persistence timers and existing provider timer histogram.
- [ ] Confirm Prometheus target and Grafana existing panels on an authorized deployment.

### 4. Production measurement gate and conditional concurrency
- [ ] Capture comparable live p50/p95, valid JSON %, length %, reasoning output tokens, token cost and backlog.
- [ ] Only if serial backlog is confirmed and lease/idempotency design reviewed, implement bounded workers in a separate change.
- [ ] Validate Haiku 5.5 Google Vertex route compatibility for both reasoning states with authorized credentials.

**Risk ruling:** Do not implement concurrency or claim performance improvement without live workload evidence; an unbounded scheduler/lease change risks duplicate billable calls and actions.
