import makeWASocket, { DisconnectReason, useMultiFileAuthState, type WASocket } from "@whiskeysockets/baileys";
import { Boom } from "@hapi/boom";
import pino, { type Logger } from "pino";
import qrcode from "qrcode-terminal";
import type { Config } from "./config.js";
import { shouldProcessUpsert, type IncomingMessage } from "./message-filter.js";
import { recordNotifyMessages } from "./notify-handler.js";
import type { WatcherState } from "./watcher-state.js";

export class WhatsAppWatcher {
  private socket?: WASocket; private reconnectTimer?: NodeJS.Timeout; private reconnectAttempt = 0; private stopped = false;
  private readonly baileysLog: Logger; private readonly names = new Map<string, string>();
  constructor(private config: Config, private state: WatcherState, private log: Logger) { this.baileysLog = pino({ level: "silent" }); }
  async start(): Promise<void> { this.stopped = false; await this.connect(); }
  private async connect(): Promise<void> {
    if (this.stopped) return;
    const { state, saveCreds } = await useMultiFileAuthState(`${this.config.dataDir}/auth`);
    const socket = makeWASocket({ auth: state, logger: this.baileysLog, printQRInTerminal: false }); this.socket = socket;
    socket.ev.on("creds.update", () => { void saveCreds(); });
    socket.ev.on("connection.update", (update) => {
      if (update.qr) { this.log.info({ event: "whatsapp_pairing_required" }, "WhatsApp session needs pairing"); qrcode.generate(update.qr, { small: true }); }
      if (update.connection === "open") { this.reconnectAttempt = 0; this.log.info({ event: "whatsapp_connected", archivedChats: this.state.countArchived() }, "WhatsApp connected"); }
      if (update.connection === "close") this.onClose(socket, update.lastDisconnect?.error);
    });
    socket.ev.process(async (events: Record<string, any>) => {
      const now = new Date().toISOString();
      const history = events["messaging-history.set"];
      if (history) {
        this.state.applyChats(history.chats ?? [], now);
        for (const contact of (history.contacts ?? []) as any[]) this.rememberName(contact.id ?? contact.jid, contact);
        this.log.info({ event: "archived_chat_loaded", archivedChats: this.state.countArchived() });
      }
      if (events["contacts.upsert"]) for (const contact of events["contacts.upsert"] as any[]) this.rememberName(contact.id ?? contact.jid, contact);
      if (events["contacts.update"]) for (const contact of events["contacts.update"] as any[]) this.rememberName(contact.id ?? contact.jid, contact);
      for (const entry of (events["chats.upsert"] ?? []) as any[]) {
        const jid = entry.id ?? entry.jid; if (!jid) continue;
        this.applyChat({ jid, archived: entry.archived, displayName: this.resolveName(jid, entry), isGroup: jid.endsWith("@g.us") }, now);
        if (entry.archived) this.log.info({ event: "archived_chat_loaded", jidType: jid.endsWith("@g.us") ? "group" : "direct" });
      }
      if (events["messages.upsert"] && shouldProcessUpsert(events["messages.upsert"].type)) recordNotifyMessages(events["messages.upsert"].messages as IncomingMessage[], this.state, this.config, this.log);
      for (const update of (events["chats.update"] ?? []) as any[]) {
        const jid = update.id ?? update.jid; if (!jid) continue;
        const old = this.state.getChat(jid); const archived = update.archived;
        this.applyChat({ jid, archived, displayName: this.resolveName(jid, update) ?? old?.displayName, isGroup: jid.endsWith("@g.us") }, now);
        if (archived !== undefined && old?.archived !== Number(archived)) this.log.info({ event: "archived_chat_changed", archived: Boolean(archived), jidType: jid.endsWith("@g.us") ? "group" : "direct" });
      }
      for (const entry of [...(events["groups.upsert"] ?? []), ...(events["groups.update"] ?? [])] as any[]) {
        const jid = entry.id ?? entry.jid;
        if (!jid) continue;
        const old = this.state.getChat(jid);
        this.applyChat({ jid, displayName: entry.subject ?? entry.name ?? old?.displayName, isGroup: true }, now);
      }
      for (const removed of (events["chats.delete"] ?? []) as any[]) this.state.deleteChat(typeof removed === "string" ? removed : removed.id ?? removed.jid);
    });
  }
  private rememberName(jid: string | undefined, contact: any): void {
    if (!jid) return;
    const name = this.nameFrom(contact, jid.endsWith("@g.us"));
    if (name) {
      this.names.set(jid, name);
      const change = this.state.updateContact(jid, name);
      if (change?.nameChanged) this.log.debug({ event: "display_name_updated", jidType: jid.endsWith("@g.us") ? "group" : "direct" });
      if (change && change.oldPolicy !== change.newPolicy) this.log.info({ event: "chat_policy_changed", oldPolicy: change.oldPolicy, newPolicy: change.newPolicy, jidType: jid.endsWith("@g.us") ? "group" : "direct" });
    }
  }
  private nameFrom(value: any, group = false): string | undefined {
    return (group ? value?.subject || value?.name : value?.name || value?.notify || value?.verifiedName || value?.pushName) || undefined;
  }
  private resolveName(jid: string, value: any): string | undefined {
    const group = jid.endsWith("@g.us");
    return group ? value?.subject || value?.name || this.names.get(jid) : this.names.get(jid) || value?.notify || value?.displayName || value?.name || value?.pushName;
  }
  private applyChat(chat: { jid: string; archived?: boolean; displayName?: string | null; isGroup?: boolean }, at: string): void {
    const change = this.state.applyChat(chat, at);
    if (change.nameChanged) this.log.debug({ event: "display_name_updated", jidType: chat.jid.endsWith("@g.us") ? "group" : "direct" });
    if (change.oldPolicy && change.oldPolicy !== change.newPolicy) this.log.info({ event: "chat_policy_changed", oldPolicy: change.oldPolicy, newPolicy: change.newPolicy, jidType: chat.jid.endsWith("@g.us") ? "group" : "direct" });
  }
  private onClose(socket: WASocket, error?: Error): void {
    if (this.socket !== socket || this.stopped) return; this.socket = undefined;
    const statusCode = error ? new Boom(error).output.statusCode : undefined;
    if (statusCode === DisconnectReason.loggedOut) { this.log.error({ event: "whatsapp_logged_out" }, "WhatsApp logged out; manual pairing is required"); return; }
    this.reconnectAttempt++; const delayMs = Math.min(60_000, 1000 * 2 ** Math.min(this.reconnectAttempt - 1, 6));
    this.log.warn({ event: "whatsapp_reconnect_scheduled", delayMs }, "WhatsApp disconnected; reconnect scheduled");
    this.reconnectTimer = setTimeout(() => { void this.connect(); }, delayMs);
  }
  async stop(): Promise<void> { this.stopped = true; if (this.reconnectTimer) clearTimeout(this.reconnectTimer); this.socket?.end(undefined); this.socket = undefined; }
}
