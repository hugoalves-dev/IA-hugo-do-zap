import { isWithinWorkHours, localIso, type Config } from "./config.js";
import type { WatcherState } from "./watcher-state.js";
import type { ConnectionLostEvent } from "./connection-events.js";
type Logger = { info: (data: object, message?: string) => void; warn: (data: object, message?: string) => void; error: (data: object, message?: string) => void };
export class Notifier {
  constructor(private config: Config, private state: WatcherState, private log: Logger, private fetcher: typeof fetch = fetch, private now: () => Date = () => new Date()) {}
  async notifyPending(): Promise<void> {
    const pending = this.state.getArchivedPending(this.state.getPending(this.config.notificationMaxChats));
    const inWorkWindow = isWithinWorkHours(this.now(), this.config);
    const currentPending = this.state.getArchivedPending(pending);
    const chats = currentPending.filter((chat) => chat.notificationPolicy !== "WORK_ONLY" || inWorkWindow);
    const deferredWorkOnlyChats = currentPending.filter((chat) => chat.notificationPolicy === "WORK_ONLY" && !inWorkWindow).length;
    this.log.info({ event: "notification_flush_started", pendingChats: pending.length, eligibleChats: chats.length, deferredWorkOnlyChats });
    if (!chats.length) return;
    const snapshots = chats.map(({ jid, messageCount, firstMessageAt, lastMessageAt }) => ({ jid, messageCount, firstMessageAt, lastMessageAt }));
    const payloadChats = chats.map(({ canonicalJid: _canonicalJid, notificationPolicy, firstMessageAt, lastMessageAt, ...chat }) => ({ ...chat, firstMessageAt: localIso(new Date(firstMessageAt), this.config.timezone), lastMessageAt: localIso(new Date(lastMessageAt), this.config.timezone), policy: notificationPolicy }));
    const payload = { secret: this.config.webhookSecret, eventType: "archived_messages" as const, generatedAt: localIso(this.now(), this.config.timezone), totalChats: chats.length, totalMessages: chats.reduce((n, c) => n + c.messageCount, 0), chats: payloadChats };
    this.log.info({ event: "notification_batch_ready", totalChats: chats.length, totalMessages: payload.totalMessages });
    const delays = [1000, 3000, 10000];
    for (let attempt = 1; attempt <= 3; attempt++) {
      try {
        const response = await this.fetcher(this.config.webhookUrl, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(payload), signal: AbortSignal.timeout(10000) });
        if (response.status >= 200 && response.status < 300) {
          this.state.clearIfUnchanged(snapshots);
          this.log.info({ event: "notification_batch_sent", status: response.status, totalChats: chats.length });
          return;
        }
        this.log.warn({ event: "notification_batch_failed", status: response.status, attempt });
      } catch (error) { this.log.warn({ event: "notification_batch_failed", error: error instanceof Error ? error.name : "UnknownError", attempt }); }
      if (attempt < 3) await new Promise((resolve) => setTimeout(resolve, delays[attempt - 1]));
    }
  }

  async notifyConnectionLost(event: ConnectionLostEvent): Promise<void> {
    const response = await this.fetcher(this.config.webhookUrl, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ secret: this.config.webhookSecret, ...event }),
      signal: AbortSignal.timeout(10000),
    });
    if (response.status >= 200 && response.status < 300) {
      this.log.info({ event: "whatsapp_connection_alert_sent", status: response.status, manualPairingRequired: event.manualPairingRequired });
      return;
    }
    this.log.warn({ event: "whatsapp_connection_alert_failed", status: response.status, manualPairingRequired: event.manualPairingRequired }, "Connection alert webhook returned a failure");
    throw new Error(`Connection alert webhook returned ${response.status}`);
  }
}
