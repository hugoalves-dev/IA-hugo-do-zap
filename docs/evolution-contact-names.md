# Evolution contact names

The Baileys implementation in Evolution API 2.3.7 emits `contacts.upsert` and
`contacts.update` records with `remoteJid` and `pushName`. The pinned upstream
handler assigns `contacts.upsert` as
`contact.name || contact.verifiedName || contact.id.split('@')[0]`; updates use
`contact.name ?? contact.verifiedName`. It does not preserve which field
supplied the name. The `findContacts` endpoint returns the stored contact row
with that same `pushName`; it does not expose a source field. See the [pinned
handler](https://github.com/evolution-foundation/evolution-api/blob/2.3.7/src/api/integrations/channel/whatsapp/whatsapp.baileys.service.ts)
and the [contact endpoint projection](https://github.com/evolution-foundation/evolution-api/blob/2.3.7/src/api/services/channel.service.ts).

The watcher stores this ambiguous value as `NOTIFY`, never `VERIFIED` or
`CONTACT_NAME`. Incoming message `pushName` is also `NOTIFY` and is the best
available WhatsApp profile name for a personal or business account; it is not
represented as the owner's address-book name. A proven address-book name has
priority over this profile fallback. An optional local alias can be set with
the private Telegram command `/apelido <jid> <nome...>` and removed with
`/apelido <jid> remover`. The alias is only used when neither an address-book
name nor a profile name is available; it cannot override a later `pushName`.
This does not mean the address book was synchronized.

Contact events do not create LID/phone aliases. Only explicit PN/LID pair
evidence links identities; matching names never merge people or change
authorization. Telegram group notifications keep the group title separate
from the sender name on the latest preview. The webhook must be configured to
deliver contact events; this change does not configure or deploy Evolution.

Contact webhooks have no stable event ID or ordering timestamp in this
contract. Each received delivery is queued separately; repeating an identical
observation leaves the same name, but delayed or reordered observations of the
same priority may replace a newer profile name. There is no bulk backfill.
Multi-device contact events and `findContacts` do not prove that the owner's
full address book was synchronized or that it is current. Although
`verifiedName` is one input to the upstream value, the webhook does not prove
whether a displayed value came from a personal profile, a business profile,
or an owner-saved contact. No live instance payloads were inspected as part of
this repository change.
