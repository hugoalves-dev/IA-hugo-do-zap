# Task 1 report: runtime modes

## Changes

- Added explicit Compose/.env flags for global shadow simulation, real decision production, real WhatsApp sending, and Evolution HTTP transport. All real-mode flags default to `false`.
- The Spring configuration selects `HttpEvolutionActionTransport` only when all three real-mode flags are explicitly `true`. Its existing validation rejects missing credentials and non-HTTPS URLs during startup. Otherwise, the disabled transport remains selected.
- Documented global shadow behavior, the database admin pause (`telegram_admin_settings.simulation_enabled`), independent real-mode opt-ins, existing consent/policy/kill-switch gates, and HTTP 2xx delivery uncertainty.
- Added focused context tests for default OFF, independently switchable shadow mode, explicit transport selection, and startup rejection without credentials. Existing WhatsApp outbox tests cover `UNKNOWN` and no retry after ambiguous results.

## Verification

- TDD RED: `mvn -Dtest=EvolutionActionTransportConfigurationTest test` failed as expected: explicit settings selected the disabled transport and missing credentials did not fail startup.
- Focused GREEN: `mvn '-Dtest=EvolutionActionTransportConfigurationTest,WhatsAppActionServiceTest' test` passed (17 tests, 0 failures/errors; Docker-disabled Testcontainers tests may be skipped by the test environment).
- Compose: `docker compose --env-file .env.example config --quiet` exited successfully using only synthetic example values.
- `git diff --check` passed.

No real endpoint, VPS, container startup, pairing, or WhatsApp send was used.
