# Evolution contact names

The Baileys implementation in Evolution API 2.3.7 emits `contacts.upsert` and
`contacts.update` records with `remoteJid` and `pushName`. The upstream handler
sets `pushName` from `contact.name` or `contact.verifiedName`, and can fall back
to the JID's numeric portion. It does not preserve which field supplied the
name. See [the pinned handler](https://github.com/evolution-foundation/evolution-api/blob/2.3.7/src/api/integrations/channel/whatsapp/whatsapp.baileys.service.ts).

The watcher stores this ambiguous value as `NOTIFY`, never `VERIFIED` or
`CONTACT_NAME`. Incoming direct message `pushName` is also `NOTIFY`. The
existing name validation and precedence apply, and contact events do not
create LID/phone aliases. Group names and outbound message names are not
derived from these observations. The webhook must be configured to deliver
contact events; this change does not configure or deploy Evolution.

Contact webhooks have no stable event ID or ordering timestamp in this
contract. Each received delivery is queued separately; repeating an identical
observation leaves the same name, but delayed or reordered observations of the
same priority may replace a newer name. There is no bulk backfill. The contract
does not establish whether a business name was verified by WhatsApp, so that
distinction requires another independently verified source.
