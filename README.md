# WhatsApp archived contact watcher

This personal service notices incoming messages from one configured contact and posts a metadata-only notification to the existing n8n webhook at the configured scheduled time. It never replies, sends email, marks messages read, changes archive status, downloads media, or stores message contents.

## Configure and run

Set `TARGET_PHONE` in `.env` to the contact's country code, area code, and number (digits only). `TARGET_LABEL` is optional. `NOTIFY_CRON` defaults to 17:30 in `America/Sao_Paulo`. Keep `.env` private.

```sh
docker compose up -d --build
docker compose ps
docker compose logs -f watcher
docker compose restart watcher
docker compose down
```

The QR, when needed, appears in watcher logs. Auth files and SQLite state persist in `./data`. The database stores pending counts/timestamps and hashes of message IDs, never message text or media. The container joins the existing `infra_network` only to reach n8n internally.

To intentionally pair again, first stop the service, then remove only `/docker/whatsapp-archived-watcher/data/auth` and start it again. Deleting `data/auth` removes the stored linked-device session and requires WhatsApp pairing again; do not do this for routine troubleshooting.

## Manual webhook test

After setting `TARGET_PHONE`, run `docker compose run --rm watcher npm run test-notification`. It sends exactly one synthetic metadata payload, bypasses SQLite, and does not require a real message. Do not run it until the n8n workflow is ready if you do not want a test event delivered.
