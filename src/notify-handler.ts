import type { Config } from "./config.js";
import { isEligibleMessage, messageIdentity, type IncomingMessage } from "./message-filter.js";
import type { WatcherState } from "./watcher-state.js";

type Logger = { info: (data: object, message?: string) => void };
export function recordNotifyMessages(messages: IncomingMessage[], state: WatcherState, config: Config, log: Logger, now: () => Date = () => new Date()): void {
  for (const message of messages) {
    if (!isEligibleMessage(message, config.includeGroups)) continue;
    const jid = message.key.remoteJid!; const chat = state.getChat(jid);
    if (!chat) { log.info({ event: "archive_state_unknown", jidType: jid.endsWith("@g.us") ? "group" : "direct" }); continue; }
    if (!chat.archived) continue;
    const receivedAt = now();
    const accepted = state.recordIncoming(jid, messageIdentity(message), receivedAt.toISOString());
    log.info({ event: accepted ? "archived_message_detected" : "duplicate_message_ignored", jidType: jid.endsWith("@g.us") ? "group" : "direct" });
  }
}
