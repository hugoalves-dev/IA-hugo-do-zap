# Evolution fixtures

`messages-upsert-synthetic.json` is a hand-authored synthetic fixture. It is not a captured webhook and does not establish compatibility with any deployed Evolution build.

The field selection is based on the official Evolution Baileys integration source: [Evolution API `whatsapp.baileys.service.ts`](https://github.com/evolution-foundation/evolution-api/blob/main/src/api/integrations/channel/whatsapp/whatsapp.baileys.service.ts). That source is the moving `main` branch; this repository currently pins no Evolution release, commit, or image digest. The fixture therefore remains synthetic until a sanitized payload from the exact pinned release is validated.

The official source shows the `messages.upsert` handler path and uses Baileys message timestamps. Its `chats.update` handler currently forwards only `remoteJid` and `instanceId`; it does not forward `archived`. As a result, absent or null `archived` is UNKNOWN, and no real Evolution archive snapshot is claimed by this fixture or mapper. The mapper retains an explicit Boolean only when one is actually present in the received data; consumers must still treat unsupported or stale snapshots as unknown.

JID alternate fields are deliberately not extracted by the mapper. The source declares `remoteJidAlt` and `participantAlt` as optional extensions, but a declaration alone does not prove that a webhook contains a valid pair. `IdentityService` joins supplied aliases only when the caller provides `EXPLICIT_ALTERNATE_JID_PAIR` evidence. It never derives a phone number from a LID.
