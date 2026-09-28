import type { Config } from "./config.js";
import { isEligibleMessage, messageIdentity, type IncomingMessage } from "./message-filter.js";
import type { WatcherState } from "./watcher-state.js";
import { cleanUsername, isLidJid, pnJid, usernameFromJid } from "./contact-identity.js";

type Logger = { info: (data: object, message?: string) => void };
export function persistMessageIdentity(message: Pick<IncomingMessage, "key">, state: WatcherState): void {
  const key = message.key;
  const remoteJid = key.remoteJid ?? undefined;
  if (remoteJid && !remoteJid.endsWith("@g.us")) state.observeIdentity({
    preferredJid: remoteJid,
    lidJid: isLidJid(remoteJid) ? remoteJid : isLidJid(key.remoteJidAlt) ? key.remoteJidAlt! : undefined,
    phoneJid: pnJid(remoteJid) ?? pnJid(key.remoteJidAlt),
    username: cleanUsername(key.remoteJidUsername) ?? usernameFromJid(remoteJid),
  });
  const participant = key.participant ?? undefined;
  if (participant) state.observeIdentity({
    preferredJid: participant,
    lidJid: isLidJid(participant) ? participant : isLidJid(key.participantAlt) ? key.participantAlt! : undefined,
    phoneJid: pnJid(participant) ?? pnJid(key.participantAlt),
    username: cleanUsername(key.participantUsername) ?? usernameFromJid(participant),
  });
}

export function recordNotifyMessages(messages: IncomingMessage[], state: WatcherState, config: Config, log: Logger, now: () => Date = () => new Date()): void {
  for (const message of messages) {
    if (!isEligibleMessage(message, config.includeGroups)) continue;
    const jid = message.key.remoteJid!; const chat = state.getChat(jid);
    if (!chat) { log.info({ event: "archive_state_unknown", jidType: jid.endsWith("@g.us") ? "group" : "direct" }); continue; }
    if (!chat.archived) continue;
    const receivedAt = now();
    const accepted = state.recordIncoming(chat.jid, messageIdentity({ ...message, key: { ...message.key, remoteJid: chat.jid } }), receivedAt.toISOString());
    log.info({ event: accepted ? "archived_message_detected" : "duplicate_message_ignored", jidType: jid.endsWith("@g.us") ? "group" : "direct" });
  }
}
