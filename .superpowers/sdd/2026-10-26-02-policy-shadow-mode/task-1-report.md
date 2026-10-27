# Task 1 — Profiles, overrides, and availability

## Implemented

- Added field-level policy inheritance: global → profile → chat override, with the resolved value and its source retained per field.
- Added expiring global availability overrides, work windows, and a PostgreSQL-backed holiday lookup. The spec's `/ocupado`, `/disponivel`, and `/ferias` states apply to the user across chats; `GlobalPolicy` and the singleton `availability_overrides` table now match that scope.
- Added migration V13 with global policy, seeded `WORK_ONLY` and `ANYTIME` profiles, per-chat policy fields, temporary global availability state, and an empty holiday calendar. No municipal holiday was presumed or seeded.
- V14 records `validated_by` and `validated_at`. A maintainer must manually compare each entry with its cited publication before marking it `VALIDATED`; the system does not fetch or fact-check source URLs. V14 downgrades pre-existing `VALIDATED` entries without this attestation to `UNKNOWN`.
- Kept the current `ALL`, `ARCHIVED_ONLY`, `UNARCHIVED_ONLY`, legacy dispatch, and direct Telegram flows intact. Migration coverage verifies existing archive mode and Telegram rate-limit state survive upgrade from V12.

## RED → GREEN

- RED: `mvn '-Dtest=PolicyResolverTest,AvailabilityServiceTest' test` failed during test compilation because the policy and availability classes did not exist yet.
- During integration, PostgreSQL 17/Testcontainers exposed a missing closing parenthesis in the V13 holiday table constraint. Fixed the DDL before the final run.
- GREEN: `mvn '-Dtest=PolicyResolverTest,AvailabilityServiceTest,DatabaseMigrationTest,SpringFlywayStartupTest' test` passed: 10 tests, 0 failures, 0 errors, 0 skipped. This included real PostgreSQL 17.11 containers.
- Coverage includes partial override and inheritance restoration, temporary override precedence and expiry, optional holiday opt-in, validated SP/Lins fixtures across a year boundary, unknown holiday with no source, V12 upgrade preservation, and Spring Flyway startup.

## Notes

- CodeGraph had no indexed nodes in this worktree; targeted source searches and direct reads were used as fallback.
- The requested RTK file was unavailable at `C:\Users\vitor.hugo\.codex-cli-private\RTK.md`; native PowerShell and Maven commands were used.
- `.idea/` and `target/` were pre-existing untracked worktree content and remain excluded from the task commit.
- No full suite, VPS operation, or plan 3 work was performed.

## Review follow-up

- RED: `mvn '-Dtest=PolicyResolverTest,AvailabilityServiceTest,SpringFlywayStartupTest' test` failed at test compilation because global override persistence and the `GlobalPolicy` override field were not implemented.
- GREEN: `mvn '-Dtest=PolicyResolverTest,AvailabilityServiceTest,DatabaseMigrationTest,SpringFlywayStartupTest' test` passed: 13 tests, 0 failures, 0 errors, 0 skipped, using PostgreSQL 17.11/Testcontainers.
- Coverage verifies global override persistence and holiday precedence, exact expiry behavior, São Paulo local-date lookup across UTC midnight, mandatory maintainer attestation fields, and downgrade of unattested legacy entries to `UNKNOWN`.
