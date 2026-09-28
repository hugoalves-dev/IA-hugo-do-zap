import makeWASocket, { DisconnectReason, useMultiFileAuthState, type WASocket } from "@whiskeysockets/baileys";
import { Boom } from "@hapi/boom";
import pino, { type Logger } from "pino";
import qrcode from "qrcode-terminal";
import type { Config } from "./config.js";
import { shouldProcessUpsert, type IncomingMessage } from "./message-filter.js";
import { clearReadNotificationQueues, persistMessageIdentity, recordNotifyMessages } from "./notify-handler.js";
import type { WatcherState } from "./watcher-state.js";
import type { Notifier } from "./notifier.js";
import { ConnectionOutageGuard } from "./connection-events.js";
import { cleanUsername, isLidJid, isTechnicalDisplayName, pnJid, usernameFromJid } from "./contact-identity.js";

function toPhoneJid(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  if (pnJid(value)) return value;
  return /^\d{5,15}$/.test(value) ? `${value}@s.whatsapp.net` : undefined;
}

export class WhatsAppWatcher {
  private socket?: WASocket; private reconnectTimer?: NodeJS.Timeout; private reconnectAttempt = 0; private stopped = false;
  private readonly baileysLog: Logger; private readonly names = new Map<string, string>();
  private readonly outageGuard: ConnectionOutageGuard;
  constructor(private config: Config, private state: WatcherState, private log: Logger, notifier: Notifier) {
    this.baileysLog = pino({ level: "silent" });
    this.outageGuard = new ConnectionOutageGuard((event) => notifier.notifyConnectionLost(event), log);
  }
  async start(): Promise<void> { this.stopped = false; await this.connect(); }
  private async connect(): Promise<void> {
    if (this.stopped) return;
    const { state, saveCreds } = await useMultiFileAuthState(`${this.config.dataDir}/auth`);
    const socket = makeWASocket({ auth: state, logger: this.baileysLog, printQRInTerminal: false }); this.socket = socket;
    socket.ev.on("creds.update", () => { void saveCreds(); });
    socket.ev.on("connection.update", (update) => {
      if (update.qr) { this.log.info({ event: "whatsapp_pairing_required" }, "WhatsApp session needs pairing"); qrcode.generate(update.qr, { small: true }); }
      if (update.connection === "open") { this.reconnectAttempt = 0; this.outageGuard.connectionOpened(); this.log.info({ event: "whatsapp_connected", archivedChats: this.state.countArchived() }, "WhatsApp connected"); void this.backfillLocalLidMappings(socket); }
      if (update.connection === "close") this.onClose(socket, update.lastDisconnect?.error);
    });
    socket.ev.process(async (events: Record<string, any>) => {
      const now = new Date().toISOString();
      const history = events["messaging-history.set"];
      if (history) {
        this.state.applyChats(history.chats ?? [], now);
        for (const mapping of history.lidPnMappings ?? []) this.rememberMapping(mapping.lid, mapping.pn);
        for (const contact of (history.contacts ?? []) as any[]) this.rememberName(contact.id ?? contact.jid, contact);
        for (const message of history.messages ?? []) this.rememberMessageIdentity(message);
        this.log.info({ event: "archived_chat_loaded", archivedChats: this.state.countArchived() });
      }
      if (events["contacts.upsert"]) for (const contact of events["contacts.upsert"] as any[]) this.rememberName(contact.id ?? contact.jid, contact);
      if (events["contacts.update"]) for (const contact of events["contacts.update"] as any[]) this.rememberName(contact.id ?? contact.jid, contact);
      const upsert = events["messages.upsert"];
      for (const message of (upsert?.messages ?? []) as any[]) this.rememberMessageIdentity(message);
      const mappingEvents = events["lid-mapping.update"];
      for (const mapping of (Array.isArray(mappingEvents) ? mappingEvents : mappingEvents ? [mappingEvents] : []) as any[]) this.rememberMapping(mapping.lid, mapping.pn);
      for (const entry of (events["chats.upsert"] ?? []) as any[]) {
        const jid = entry.id ?? entry.jid; if (!jid) continue;
        this.applyChat({ jid, archived: entry.archived, displayName: this.resolveName(jid, entry), isGroup: jid.endsWith("@g.us") }, now);
        if (entry.archived) this.log.info({ event: "archived_chat_loaded", jidType: jid.endsWith("@g.us") ? "group" : "direct" });
      }
      if (upsert && shouldProcessUpsert(upsert.type)) recordNotifyMessages(upsert.messages as IncomingMessage[], this.state, this.config, this.log);
      clearReadNotificationQueues({ chatsUpdate: events["chats.update"] ?? [], messagesUpdate: events["messages.update"] ?? [] }, this.state, this.log);
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
    const group = jid.endsWith("@g.us");
    const phoneJid = toPhoneJid(contact?.phoneNumber) ?? pnJid(jid);
    const lidJid = isLidJid(contact?.lid) ? contact.lid : isLidJid(jid) ? jid : undefined;
    const username = cleanUsername(contact?.username) ?? usernameFromJid(jid);
    const candidates = [[contact?.name,"contact_name"],[contact?.notify,"notify"],[contact?.verifiedName,"verified"]] as const;
    const candidate = group ? contact?.subject ?? contact?.name : candidates.find(([name]) => name && !isTechnicalDisplayName(name));
    const name = typeof candidate === "string" ? candidate : candidate?.[0];
    const source = group ? "chat" : candidate?.[1];
    const old = this.state.getChat(jid);
    if (!group) this.state.observeIdentity({ preferredJid: jid, lidJid, phoneJid, username, displayName: name, displayNameSource: source as any });
    else if (name) this.state.applyChat({ jid, displayName: name, isGroup: true });
    const current = this.state.getChat(jid);
    const resolved = this.state.resolveIdentity(jid).displayName;
    if (resolved && resolved !== jid && !isTechnicalDisplayName(resolved)) {
      this.names.set(jid, resolved);
      if (lidJid) this.names.set(lidJid, resolved);
      if (phoneJid) this.names.set(phoneJid, resolved);
    }
    if (old?.displayName !== resolved) this.log.debug({ event: "display_name_updated", jidType: group ? "group" : "direct" });
    if (old && current && old.notificationPolicy !== current.notificationPolicy) this.log.info({ event: "chat_policy_changed", oldPolicy: old.notificationPolicy, newPolicy: current.notificationPolicy, jidType: group ? "group" : "direct" });
    if (!old && name && !group) this.log.debug({ event: "display_name_updated", jidType: "direct" });
  }
  private resolveName(jid: string, value: any): string | undefined {
    const group = jid.endsWith("@g.us");
    if (group) return value?.subject || value?.name || this.names.get(jid);
    const resolved = this.state.resolveIdentity(jid).displayName;
    if (resolved !== jid && !isTechnicalDisplayName(resolved)) return resolved;
    const candidates = [value?.notify, value?.displayName, value?.name, value?.pushName, this.names.get(jid)];
    return candidates.find((name) => typeof name === "string" && !isTechnicalDisplayName(name));
  }
  private applyChat(chat: { jid: string; archived?: boolean; displayName?: string | null; isGroup?: boolean }, at: string): void {
    const change = this.state.applyChat(chat, at);
    if (change.nameChanged) this.log.debug({ event: "display_name_updated", jidType: chat.jid.endsWith("@g.us") ? "group" : "direct" });
    if (change.oldPolicy && change.oldPolicy !== change.newPolicy) this.log.info({ event: "chat_policy_changed", oldPolicy: change.oldPolicy, newPolicy: change.newPolicy, jidType: chat.jid.endsWith("@g.us") ? "group" : "direct" });
  }
  private rememberMapping(lid?: string | null, pn?: string | null): void {
    if (!lid || !pn) return;
    this.state.observeIdentity({ preferredJid: this.state.getChat(lid)?.jid ?? this.state.getChat(pn)?.jid ?? lid, lidJid: lid, phoneJid: pnJid(pn) });
  }
  private rememberMessageIdentity(message: any): void {
    if (message?.key) persistMessageIdentity(message, this.state);
  }
  private async backfillLocalLidMappings(socket: WASocket): Promise<void> {
    const lids = this.state.lidsForLocalBackfill();
    for (let offset = 0; offset < lids.length; offset += 100) {
      try {
        const mappings = await socket.signalRepository.lidMapping.getPNsForLIDs(lids.slice(offset, offset + 100));
        for (const mapping of mappings ?? []) this.rememberMapping(mapping.lid, mapping.pn);
      } catch (error) {
        this.log.warn({ event: "lid_mapping_backfill_failed", error: error instanceof Error ? error.name : "UnknownError" });
        return;
      }
    }
  }
  private onClose(socket: WASocket, error?: Error): void {
    if (this.socket !== socket || this.stopped) return; this.socket = undefined;
    const statusCode = error ? new Boom(error).output.statusCode : undefined;
    const loggedOut = statusCode === DisconnectReason.loggedOut;
    this.outageGuard.connectionLost({ reason: loggedOut ? "logged_out" : statusCode === undefined ? "connection_closed" : DisconnectReason[statusCode] ?? "connection_closed", statusCode, manualPairingRequired: loggedOut });
    if (loggedOut) { this.log.error({ event: "whatsapp_logged_out", statusCode }, "WhatsApp logged out; manual pairing is required"); return; }
    this.reconnectAttempt++; const delayMs = Math.min(60_000, 1000 * 2 ** Math.min(this.reconnectAttempt - 1, 6));
    this.log.warn({ event: "whatsapp_reconnect_scheduled", delayMs }, "WhatsApp disconnected; reconnect scheduled");
    this.reconnectTimer = setTimeout(() => { void this.connect(); }, delayMs);
  }
  async stop(): Promise<void> { this.stopped = true; this.outageGuard.cancelPending(); if (this.reconnectTimer) clearTimeout(this.reconnectTimer); this.socket?.end(undefined); this.socket = undefined; }
}
