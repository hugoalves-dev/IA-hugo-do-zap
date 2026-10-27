# Task 1 report — Classification and privacy

Date: 2026-10-27

## Scope

- Added `ClassificationInput` extraction, truncation, and deterministic masks for common phones, emails, WhatsApp JIDs, CPF/CNPJ values, UUIDs, and labeled IDs. Tests preserve ordinary amounts, dates, and short numbers.
- This is minimization, not anonymization. Names and sensitive free-form text are not reliably detected or removed.
- Added strict classification result schema and semantic consistency checks. Confidence below `0.65`, unknown intent, or unknown urgency produces `UNCERTAIN` with no action-like fields.
- Added HTTP providers with mandatory `ProviderAuthorization`. `ProviderAuthorization.denyAll()` is the safe default. No Spring bean or simulation wiring invokes providers at runtime; a future wiring must supply explicit budget/data authorization.
- Gemini is primary. OpenRouter fallback runs only after a Gemini technical failure. Authorization, privacy-route, configuration, and invalid-schema errors do not trigger fallback.
- Gemini allows one schema repair attempt and reauthorizes each HTTP attempt. HTTP timeout/IO failures are technical failures.
- OpenRouter requests always require `provider.zdr=true`, `provider.data_collection=deny`, and disable provider fallbacks. A 404 means private route unavailable and fails closed.
- Gemini endpoint is limited to `generativelanguage.googleapis.com`; OpenRouter endpoint is limited to `openrouter.ai`. HTTPS is mandatory. User info, query strings, and fragments are rejected.
- Tests inject an in-memory transport; production JDK transport cannot send HTTP and uses `Redirect.NEVER`.
- HTTP response bodies are collected asynchronously with a 64 KiB cap and an end-to-end deadline. Timeout cancels both the request future and body subscription. Oversized responses cancel the subscription and fail as `RESPONSE_TOO_LARGE`.
- The local body-size and routing controls make no claim about provider retention or handling.
- Simulation was not integrated. Plan 2 has not provided a budget reservation service, so wiring it now could call a paid provider without a reservation.
- No paid provider, VPS, key, or real message payload was used. Tests use synthetic authorization and an in-memory transport; deadline tests inject a synthetic HTTP exchange without sockets.

## RED / GREEN

- RED: `mvn -Dtest=ClassificationServiceTest,GeminiProviderTest,OpenRouterProviderTest test` failed compilation because the planned `ai` production types did not yet exist.
- GREEN: the same focused test command passed from an isolated scratch copy of the checkout: 14 tests, 0 failures, 0 errors, 0 skipped.
- Coverage includes Gemini timeout and schema retry, OpenRouter privacy flags and unavailable route, prompt-injection handling, identifier minimization, fail-closed authorization, low-confidence uncertainty, and technical-only fallback.
- Follow-up RED: endpoint, minimization, and size tests first failed on unmasked values, HTTP destinations, oversized bodies, and cross-provider host acceptance. GREEN covers those regressions, a suspended-body timeout with cancellation, redirect handling, and date/amount preservation.
- Minimizer RED against the prior version: it changed `27/10/2026` and `2026-10-27` to `[PHONE]`. The revised phone patterns preserve those dates while masking the phone examples.
- Final focused task run: 25 tests across `ClassificationServiceTest`, `ClassificationInputMinimizerTest`, `GeminiProviderTest`, `JdkProviderTransportTest`, `OpenRouterProviderTest`, and `ProviderEndpointPolicyTest`; 0 failures, 0 errors, 0 skipped. Run used the isolated scratch copy.
- CodeGraph returned zero indexed nodes; scoped `rg` searches and source reads traced `Batch`, `IncomingMessage`, and the existing simulation path. `SimulationService` remains unchanged.

## Official API references checked

- Gemini structured output accepts JSON Schema configuration and still requires application-side semantic validation: [Gemini structured output](https://ai.google.dev/gemini-api/docs/structured-output).
- OpenRouter supports per-request `zdr: true` and `data_collection: "deny"`; its docs describe routing only to eligible providers: [OpenRouter ZDR and data collection](https://openrouter.ai/docs/guides/get-started/sovereign-ai).
- OpenRouter chat completion uses its documented `/api/v1/chat/completions` request format and provider options: [OpenRouter chat completion API](https://openrouter.ai/docs/api/api-reference/chat/send-chat-completion-request?explorer=true).

## Workspace note

The first Maven invocation ignored `-Dproject.build.directory` and wrote generated output under the pre-existing untracked `target/`. Subsequent Maven verification ran only from the scratch copy. `.idea/` and `target/` were not staged or included in the task commit. The pre-existing generated `target/` artifacts may have been refreshed by that first command.
