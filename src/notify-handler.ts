import type { Config } from "./config.js";
import { WAMessageStatus } from "@whiskeysockets/baileys";
import { isEligibleMessage, messageIdentity, type IncomingMessage } from "./message-filter.js";
import type { WatcherState } from "./watcher-state.js";
import { cleanUsername, isLidJid, pnJid, usernameFromJid } from "./contact-identity.js";
import { extractMessagePreview } from "./message-preview.js";
import { isNeverNotifyGroup } from "./config.js";

type Logger = { info: (data: object, message?: string) => void };
interface ReadEvents {
  chatsUpdate?: Array<{ id?: string | null; jid?: string | null; unreadCount?: number | null }>;
  messagesUpdate?: Array<{ key?: { remoteJid?: string | null; fromMe?: boolean | null }; update?: { status?: number | null } }>;
}

export function clearReadNotificationQueues(events: ReadEvents, state: WatcherState, log: Logger): number {
  const readChats = new Set<string>();
  for (const update of events.chatsUpdate ?? []) {
    const jid = update.id ?? update.jid;
    if (jid && update.unreadCount === 0) readChats.add(jid);
  }
  for (const update of events.messagesUpdate ?? []) {
    const jid = update.key?.remoteJid;
    if (jid && update.key?.fromMe === false && update.update?.status === WAMessageStatus.READ) readChats.add(jid);
  }

  let cleared = 0;
  for (const jid of readChats) {
    const removed = state.clearPendingForChat(jid);
    cleared += removed;
    if (removed) log.info({ event: "pending_notifications_cleared_read", jidType: jid.endsWith("@g.us") ? "group" : "direct", pendingChatsCleared: removed });
  }
  return cleared;
}

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
    if (isNeverNotifyGroup(chat.displayName, Boolean(chat.isGroup), config.neverNotifyGroupNames)) {
      state.clearPendingForChat(chat.jid);
      log.info({ event: "notification_suppressed_by_group_name" });
      continue;
    }
    const receivedAt = now();
    const preview = extractMessagePreview(message.message);
    const accepted = state.recordIncoming(chat.jid, messageIdentity({ ...message, key: { ...message.key, remoteJid: chat.jid } }), receivedAt.toISOString(), preview);
    log.info({ event: accepted ? "archived_message_detected" : "duplicate_message_ignored", jidType: jid.endsWith("@g.us") ? "group" : "direct" });
  }
}
