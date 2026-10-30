# Telegram admin: migration from long polling to HTTPS webhook (issue #17)

The Spring Boot process owns the Telegram admin policy, callback token validation, commands,
and PostgreSQL notification outbox. Telegram sends admin commands to a **dedicated public HTTPS**
path: `POST /api/webhooks/telegram`. **No other Spring admin or Actuator endpoint should be public.**
Legacy Evolution webhook, WhatsApp notifications, simulations, and existing secrets are unaffected.

## Prerequisites

1. Verify the deployment is healthy and back up the watcher PostgreSQL database.
   Flyway V34 adds the `telegram_webhook_inbox` table and indexes.
2. Prepare Cloudflare Tunnel with the existing internal `watcher:8080` origin on `infra_network`.
   Publish a dedicated hostname, e.g. `telegram-webhook.example.com`, and restrict requests
   at Cloudflare to **only** `POST /api/webhooks/telegram` (deny all other paths/methods).
   If the hostname is protected by Cloudflare Access, set an **Access Bypass policy only for
   this exact route** so Telegram does not receive a login challenge. Prefer Cloudflare WAF
   custom rules plus reasonable rate limiting. The backend also validates the Telegram secret.
   **Never** bypass Access for the whole hostname when it exposes other applications.
3. Generate a strong URL-safe `secret_token` (32–256 chars of `A-Z a-z 0-9 _ -`) and store
   it only in the protected VPS `.env`. Keep `WATCHER_TELEGRAM_ADMIN_USER_ID`,
   `WATCHER_TELEGRAM_CHAT_ID`, and the existing bot token unchanged. Do not log either secret.
4. Start with `WATCHER_TELEGRAM_ADMIN_WEBHOOK_ENABLED=false`. Both webhook and polling
   flags cannot be true in the same app. All new real WhatsApp action flags remain **off**.

## Cutover (maintenance window)

1. Ensure the public HTTPS route and WAF allow Telegram POST traffic to the Spring origin.
   Temporarily stop the old polling consumer: deploy with
   `WATCHER_TELEGRAM_ADMIN_POLLING_ENABLED=false` and
   `WATCHER_TELEGRAM_ADMIN_WEBHOOK_ENABLED=true` and the secret configured.
   Validate readiness first. The application fails startup on missing/short secret or
   simultaneous polling and webhook configuration.
2. Register the official Bot API webhook from a trusted shell (do not place the bot token
   in shell history or a CI log). For example, export `BOT_TOKEN`, `WEBHOOK_SECRET`,
   and `WEBHOOK_URL=https://telegram-webhook.example.com/api/webhooks/telegram` securely,
   then run:

   ```bash
   curl --fail-with-body --silent --show-error \
     --url "https://api.telegram.org/bot${BOT_TOKEN}/setWebhook" \
     --data-urlencode "url=${WEBHOOK_URL}" \
     --data-urlencode "secret_token=${WEBHOOK_SECRET}" \
     --data-urlencode 'allowed_updates=["message","callback_query"]' \
     --data-urlencode 'max_connections=2' \
     --data-urlencode 'drop_pending_updates=false'
   ```

3. Check `getWebhookInfo`, send `/menu` and `/status` from the authorized private chat,
   navigate via a button, confirm and cancel a safe test command, and ensure each callback
   spinner is dismissed. Test unauthenticated POST (401), duplicate update ID (200),
   and invalid JSON (400) without exposing credentials. Check `pending_update_count`
   and last webhook error.
4. Observe inbox and outbox health:

   ```sql
   SELECT status, count(*) FROM telegram_webhook_inbox GROUP BY status;
   SELECT update_id, attempts, last_error, received_at, available_at
   FROM telegram_webhook_inbox WHERE status <> 'DONE' ORDER BY received_at LIMIT 20;
   SELECT action_type, status, count(*) FROM outbox_actions
   WHERE action_type IN ('telegram_admin_reply','telegram_admin_edit')
   GROUP BY action_type, status;
   ```

   The worker commits command, audit, callback token consumption and outbox effects with
   `DONE` in one database transaction. Telegram HTTP acknowledgments happen after commit.
   Retries are deduplicated by unique `update_id`. `DEAD` rows need operator review.
   Completed payloads are blanked; terminal metadata is automatically purged after 7 days.

## Rollback

1. Use `deleteWebhook` **without** discarding pending Telegram updates:

   ```bash
   curl --fail-with-body --silent --show-error \
      --url "https://api.telegram.org/bot${BOT_TOKEN}/deleteWebhook" \
      --data-urlencode 'drop_pending_updates=false'
   ```

2. Set `WATCHER_TELEGRAM_ADMIN_WEBHOOK_ENABLED=false` and
   `WATCHER_TELEGRAM_ADMIN_POLLING_ENABLED=true`, deploy and validate the poller.
   **Do not run polling and webhook consumers at the same time.**
3. Investigate any `PENDING` inbox rows before switching traffic back, because a
   successfully ACKed webhook update is not replayed by Telegram after `deleteWebhook`.
   Drain/reprocess them with a controlled recovery procedure; never drop rows to force
   an apparently clean dashboard.
4. If a rollout is aborted before Telegram `setWebhook`, simply leave webhook disabled
   and continue using polling. Flyway V34 is additive; no destructive downgrade is required.

## Operator notes

- `/confirm <token>` remains supported for text-only clients; the UI uses short opaque
  inline callback tokens bound to admin ID/chat, expiring after 5 minutes, with one use.
- `WATCHER_TELEGRAM_ADMIN_MINI_APP_URL` is optional. Only set it after the separate
  Mini App is live and served over HTTPS. The bot does not create a Mini App in this issue.
- `/emergencia` remains immediately available without AI; no real WhatsApp replies
  are enabled by this change.
- Watch for elevated HTTP 401/413/415/503, `DEAD` inbox rows, Bot API callback
  response failures and lag between inbox reception and outbox delivery.
- Keep Cloudflare Tunnel configuration outside git if it contains credentials; commit
  only redacted routing policies. Coordinate Access bypass with the rules in this runbook.

Official references: https://core.telegram.org/bots/api (setWebhook, getWebhookInfo,
deleteWebhook, answerCallbackQuery, inline keyboards).
