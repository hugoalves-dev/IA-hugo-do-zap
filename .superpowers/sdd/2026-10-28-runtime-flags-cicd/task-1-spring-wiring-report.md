# Task 1 follow-up: Spring constructor wiring

## Finding and fix

`MessageService` had `@Autowired` on both its 7-argument compatibility constructor and its 8-argument constructor that injects `watcher.decision-producer.real-mode-enabled`. Spring rejected the bean with `Invalid autowire-marked constructor` before completing application startup.

Removed `@Autowired` from the compatibility constructor and kept it on the 8-argument runtime constructor.

## Verification

- TDD RED: `mvn '-Dtest=MessageServiceApplicationWiringTest' test` failed both complete application contexts at bootstrap with the duplicate-constructor error.
- GREEN: same command passed (2 tests) after the annotation change.
- Focused GREEN: `mvn '-Dtest=MessageServiceApplicationWiringTest,MessageServiceTest,DebounceServiceTest,SimulationSchedulerTest,AiSimulationAcceptanceTest' test` passed (83 tests, 0 failures/errors/skips).
- Both application contexts use local PostgreSQL Testcontainers and synthetic Telegram/webhook values. Default mode and real-only mode start with Evolution sending disabled and no Evolution credentials.
- `git diff --check` passed.
