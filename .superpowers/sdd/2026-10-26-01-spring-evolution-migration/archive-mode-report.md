# Archive monitoring mode

## Implemented

- Added persistent PostgreSQL setting `archive_monitor_settings`, initialized to `ALL` by Flyway migration V11.
- Added modes `ALL`, `ARCHIVED_ONLY`, and `UNARCHIVED_ONLY`.
- `ALL` accepts messages with missing, null, or unsynchronized archive state. It does not create or infer a chat snapshot.
- Filtered modes quarantine unknown or unsynchronized state during message ingestion and exclude it at enqueue, retry, pre-delivery, and acknowledgement.
- Outbox action keys are stable for a pending generation and mode. An acknowledged action is rearmed in place only if the same snapshot is still eligible; active retry rows keep their existing backoff.
- Mode is read from PostgreSQL at runtime. Pending messages remain available when a mode change temporarily excludes them; a later mode change can make them eligible again.
- Existing read handling still clears pending only when read evidence and a synchronized archive snapshot are available.
- Group, work-window, deduplication, retention, and legacy webhook payload behavior remain in the existing paths.
- Added authenticated `GET` and `PUT /internal/archive-mode`. Configure `WATCHER_ARCHIVE_MODE_API_TOKEN` (`watcher.archive-mode.api-token`) with a dedicated secret. Without it, the API rejects all requests. PUT bodies are capped at 256 bytes.

Example:

```http
GET /internal/archive-mode
Authorization: Bearer <WATCHER_ARCHIVE_MODE_API_TOKEN>
```

```http
PUT /internal/archive-mode
Authorization: Bearer <WATCHER_ARCHIVE_MODE_API_TOKEN>
Content-Type: application/json

{"mode":"UNARCHIVED_ONLY"}
```

## Verification

Ran focused PostgreSQL Testcontainers tests with PostgreSQL 17, including the HTTP integration test:

```text
mvn -q -Dtest=ArchiveModeServiceTest,MessageServiceTest,NotificationServiceTest,ChatStateServiceTest,EvolutionPipelineIntegrationTest test
exit code: 0
```

`git diff --check` completed without whitespace errors. The complete suite was not run. No VPS, external services, or real messages were used.

## Review findings

- P1 not reproduced: a persisted action failed under `ARCHIVED_ONLY`, then mode changed to `UNARCHIVED_ONLY` before its outbox retry. The retry revalidated the current PostgreSQL mode and sent no HTTP request. `N8nClient.sendPrepared` also invokes the revalidation supplier before each internal HTTP attempt.
- P2 confirmed and fixed: changing a pending chat to an excluded state suppressed its action while preserving its generation. Restoring the eligible state and flushing created a second row because the key contained the flush timestamp. The key now uses mode, generation snapshot, and stable payload. PostgreSQL upserts an acknowledged row back to pending in place; active retry rows are not reset.
- Focused `NotificationServiceTest` after the fix passed with PostgreSQL 17/Testcontainers, including mode change between outbox failure and retry, preserved retry backoff across repeated flush, and suppressed-generation reactivation without duplicate rows.

## Operational note

In `ALL`, a message without a synchronized snapshot can be queued, but read signals cannot clear it without proven state. Such pending messages remain subject to the existing expiry and retention policy. An HTTP delivery already in flight cannot be recalled after a mode change; retry and acknowledgement paths recheck the current mode and preserve pending data when the new mode rejects it.
