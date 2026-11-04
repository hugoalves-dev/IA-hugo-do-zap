# Telegram prompt management design

## Intent

Let the authenticated administrator review and change the operational classification prompt through the Telegram bot. Manual and AI-assisted edits remain drafts until explicit confirmation. This supports safer prompt iteration without editing the VPS, environment files, or database by hand.

## Goals

- Manage the operational prompt from a private admin Telegram chat: review, edit manually, draft with AI, preview, refine, inspect history, activate, restore an earlier custom revision, or reset to the Java default.
- Keep immutable classification safety instructions and the JSON response schema outside administrator-controlled text.
- Persist drafts, custom revisions, active state, and minimal audit metadata in PostgreSQL with additive Flyway migrations.
- Apply the active prompt on the next eligible Shadow classification, without restart, and record the revision identity used for each classification.
- Keep historical decisions unchanged and do not enable live notifications, replies, or WhatsApp sends.
- Keep domain behavior reusable by a future Mini App adapter.

## Non-goals and anti-goals

- No changes to `.env`, Compose, provider activation flags, or production deployment.
- No prompt-based authorization, policy override, schema changes, or permission to trigger real actions.
- No sending private WhatsApp messages, credentials, or raw logs to the prompt drafting model.
- No rewriting or deleting prior prompt revisions when resetting to the Java default.

## Constraints and current state

- Telegram updates already require the configured administrator identity and private chat. Existing callback tokens are expiring, identity-bound, and single-use; the new flow must preserve those properties.
- OpenRouter and optional Gemini currently share one hardcoded system prompt in `AbstractHttpClassificationProvider`; provider request bodies also contain the classification schema and minimized conversation payload.
- This checkout has migrations through V54 and no prompt management service or schema. The new migration version must be selected from the checked-out migration set at implementation time.
- PostgreSQL owns durable business state. SQL stays in repositories, state transitions remain transactional, and failures fail closed.

## Approaches

1. **Focused prompt domain service with Telegram adapter (selected).** Put prompt validation, draft/revision lifecycle, active resolution, and audit decisions in a dedicated service and repository. Add Telegram commands/callbacks and private-chat draft handling as an adapter. This keeps business rules reusable for a future Mini App and matches existing transport/domain/persistence boundaries.
2. **Extend the existing admin command service.** This reduces initial wiring but would mix prompt draft state, AI generation, and version lifecycle with unrelated operational controls, making the requested future adapter harder to reuse.

The focused service is selected because issue #45 requires reusable prompt lifecycle behavior and a multi-step interactive flow.

## Behavior and implementation calls

- Persist custom revisions and drafts with author, creation time, origin (`manual` or `ai_assisted`), description, content hash, and immutable revision ID. Keep one active custom revision per scope. A null active revision explicitly means “resolve from the Java default.”
- Resolve a classification prompt consistently for each invocation. Compose the operational text with the immutable core and fixed response schema in Java; never allow stored or model-generated text to replace these protections. Store the resolved revision ID/hash with classification audit data, using revision `0` and the default hash for the Java default.
- A manual message creates or updates a bounded draft only in the authenticated private chat. It never activates a prompt. Preview and deterministic validation precede a short-lived, single-use confirmation to activate; cancellation, expiry, re-entry, and service restart cannot activate an unfinished draft.
- AI-assisted drafting sends only explicit administrator instructions and the relevant operational prompt to a configured provider, using existing provider authorization and budget controls where compatible. Generated text remains an editable draft. Bound input, output, iterations, and cost; provider failure preserves the draft. Every activation uses the same deterministic validation and explicit confirmation as manual editing.
- History is metadata-first; prompt contents appear only to the authorized admin in private chat. Restore creates a new activation event referencing an existing revision. Reset deactivates the custom override, records the transition to the Java default without fabricating a prompt revision, and preserves all revisions and drafts.
- Enforce allowed placeholders, maximum size, prompt structure/schema constraints, and prohibited instruction checks in deterministic validation. Exact limits and reusable validation rules are implementation choices subject to existing project conventions.
- Persist a classification’s prompt revision identity alongside its evaluation record, without reprocessing historical decisions. Database failure must not silently use a stale or partially applied custom prompt; fallback behavior will be fail-closed for that classification.
- Keep the feature unavailable for non-Shadow execution paths. No notification, reply, or WhatsApp action is introduced by prompt management.

## Open implementation details

The implementer will map the existing Shadow evaluation persistence and Telegram message transport, select the next migration version from the repository, and choose bounded draft-state storage and token/cost limits consistent with existing configuration. Tests will cover authorization, replay/expiry, negative message context, persistence failures, concurrent activation, reset/history semantics, provider failures, and both classification provider request schemas.
