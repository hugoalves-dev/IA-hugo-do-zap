# Archive monitoring mode

## Implemented

- Added persistent PostgreSQL setting `archive_monitor_settings`, initialized to `ALL` by Flyway migration V11.
- Added modes `ALL`, `ARCHIVED_ONLY`, and `UNARCHIVED_ONLY`.
- `ALL` accepts messages with missing, null, or unsynchronized archive state. It does not create or infer a chat snapshot.
- Filtered modes quarantine unknown or unsynchronized state during message ingestion and exclude it at enqueue, retry, pre-delivery, and acknowledgement.
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

## Operational note

In `ALL`, a message without a synchronized snapshot can be queued, but read signals cannot clear it without proven state. Such pending messages remain subject to the existing expiry and retention policy. An HTTP delivery already in flight cannot be recalled after a mode change; retry and acknowledgement paths recheck the current mode and preserve pending data when the new mode rejects it.
