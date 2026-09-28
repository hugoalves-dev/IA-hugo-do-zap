import Database from "better-sqlite3";
import { createHash } from "node:crypto";
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { classifyNotificationPolicy, type NotificationPolicy } from "./config.js";
import { cleanUsername, isLidJid, isTechnicalDisplayName, phoneNumberFromPnJid, pnJid, resolveContactDisplayName, type IdentityNameSource, usernameFromJid } from "./contact-identity.js";

export interface Chat { jid: string; archived: number; displayName: string | null; isGroup: number; updatedAt: string; notificationPolicy: NotificationPolicy }
export interface ContactIdentity { jid: string; canonicalJid: string; displayName: string; phoneJid?: string; phoneNumber?: string; lidJid?: string; username?: string; remoteJidAlt?: string }
export interface Pending extends ContactIdentity { messageCount: number; firstMessageAt: string; lastMessageAt: string; lastMessagePreview: string | null; isGroup: boolean; notificationPolicy: NotificationPolicy }
export type Snapshot = Pick<Pending, "jid" | "messageCount" | "firstMessageAt" | "lastMessageAt">;
export interface IdentityObservation { preferredJid?: string; lidJid?: string; phoneJid?: string; username?: string; displayName?: string | null; displayNameSource?: IdentityNameSource }
interface IdentityRow { canonical_jid: string; lid_jid: string | null; phone_jid: string | null; username: string | null; display_name: string | null; display_name_source: IdentityNameSource | null; updated_at: string }
interface ChatRow { jid: string; archived: number; display_name: string | null; is_group: number; updated_at: string; notification_policy: NotificationPolicy }

export class WatcherState {
  private readonly db: Database.Database;
  constructor(dataDir: string, _unusedTarget?: string, private readonly nameRegex: RegExp = /(^|[^A-Z0-9])JC([^A-Z0-9]|$)/i) {
    mkdirSync(dataDir, { recursive: true, mode: 0o700 });
    this.db = new Database(join(dataDir, "watcher.db"));
    this.db.pragma("journal_mode = WAL");
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS watcher_state (contact_phone TEXT PRIMARY KEY, pending INTEGER NOT NULL DEFAULT 0, message_count INTEGER NOT NULL DEFAULT 0, first_message_at TEXT NULL, last_message_at TEXT NULL, last_notified_at TEXT NULL);
      CREATE TABLE IF NOT EXISTS chat_state (jid TEXT PRIMARY KEY, archived INTEGER NOT NULL DEFAULT 0, display_name TEXT NULL, is_group INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS pending_notification (jid TEXT PRIMARY KEY, message_count INTEGER NOT NULL DEFAULT 0, first_message_at TEXT NOT NULL, last_message_at TEXT NOT NULL, last_message_preview TEXT NULL);
      CREATE TABLE IF NOT EXISTS processed_message (message_hash TEXT PRIMARY KEY, seen_at TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS contact_identity (
        canonical_jid TEXT PRIMARY KEY,
        lid_jid TEXT UNIQUE,
        phone_jid TEXT UNIQUE,
        username TEXT,
        display_name TEXT,
        display_name_source TEXT,
        updated_at TEXT NOT NULL
      );
      CREATE INDEX IF NOT EXISTS contact_identity_username_idx ON contact_identity(username);
    `);
    const columns = this.db.pragma("table_info(chat_state)") as Array<{ name: string }>;
    const pendingColumns = this.db.pragma("table_info(pending_notification)") as Array<{ name: string }>;
    if (!pendingColumns.some((column) => column.name === "last_message_preview")) this.db.exec("ALTER TABLE pending_notification ADD COLUMN last_message_preview TEXT NULL");
    if (!columns.some((column) => column.name === "notification_policy")) this.db.exec("ALTER TABLE chat_state ADD COLUMN notification_policy TEXT NOT NULL DEFAULT 'ANYTIME'");
    this.db.exec("UPDATE chat_state SET notification_policy='ANYTIME' WHERE notification_policy NOT IN ('ANYTIME','WORK_ONLY')");
    this.db.transaction(() => {
      const chats = this.db.prepare("SELECT jid,display_name FROM chat_state WHERE is_group=0").all() as Array<{ jid: string; display_name: string | null }>;
      for (const chat of chats) {
        const lid = isLidJid(chat.jid) ? chat.jid : undefined;
        const phoneJid = pnJid(chat.jid);
        const username = usernameFromJid(chat.jid) ?? (chat.display_name?.startsWith("@") ? usernameFromJid(chat.display_name) : undefined);
        this.observeIdentity({ preferredJid: chat.jid, lidJid: lid, phoneJid, username, displayName: isTechnicalDisplayName(chat.display_name) ? undefined : chat.display_name, displayNameSource: "legacy" });
      }
    })();
  }

  private identityFor(jid: string): IdentityRow | undefined {
    return this.db.prepare("SELECT * FROM contact_identity WHERE canonical_jid=? OR lid_jid=? OR phone_jid=? OR username=? ORDER BY CASE WHEN canonical_jid=? THEN 0 ELSE 1 END LIMIT 1").get(jid, jid, jid, jid.replace(/^@/, ""), jid) as IdentityRow | undefined;
  }
  private canonicalFor(jid: string): string { return this.identityFor(jid)?.canonical_jid ?? jid; }
  private chatRow(jid: string): ChatRow | undefined {
    return this.db.prepare("SELECT jid,archived,display_name,is_group,updated_at,notification_policy FROM chat_state WHERE jid=?").get(jid) as ChatRow | undefined;
  }
  private refreshChatName(canonicalJid: string, name: string): void {
    const current = this.chatRow(canonicalJid);
    if (!current) return;
    const policy = classifyNotificationPolicy(name, this.nameRegex);
    this.db.prepare("UPDATE chat_state SET display_name=?,notification_policy=?,updated_at=? WHERE jid=?").run(name, policy, new Date().toISOString(), canonicalJid);
  }

  observeIdentity(observation: IdentityObservation): void {
    const preferredJid = observation.preferredJid;
    const lidJid = observation.lidJid && isLidJid(observation.lidJid) ? observation.lidJid : isLidJid(preferredJid) ? preferredJid : undefined;
    const phoneJid = pnJid(observation.phoneJid) ?? pnJid(preferredJid);
    const username = cleanUsername(observation.username) ?? usernameFromJid(preferredJid);
    const now = new Date().toISOString();
    this.db.transaction(() => {
      const lookupIds = [preferredJid, lidJid, phoneJid, username].filter((x): x is string => Boolean(x));
      const matches = lookupIds.length ? this.db.prepare(`SELECT * FROM contact_identity WHERE canonical_jid IN (${lookupIds.map(() => "?").join(",")}) OR lid_jid IN (${lookupIds.map(() => "?").join(",")}) OR phone_jid IN (${lookupIds.map(() => "?").join(",")}) OR username IN (${lookupIds.map(() => "?").join(",")})`).all(...lookupIds, ...lookupIds, ...lookupIds, ...lookupIds) as IdentityRow[] : [];
      const canonicalJid = (preferredJid && matches.find((r) => r.canonical_jid === preferredJid)?.canonical_jid)
        ?? (preferredJid && this.chatRow(preferredJid) ? preferredJid : undefined)
        ?? matches[0]?.canonical_jid
        ?? (lidJid && this.chatRow(lidJid) ? lidJid : undefined)
        ?? (phoneJid && this.chatRow(phoneJid) ? phoneJid : undefined)
        ?? preferredJid ?? lidJid ?? phoneJid;
      if (!canonicalJid) return;
      const canonicalMatches = [...new Set([canonicalJid, ...matches.map((r) => r.canonical_jid), ...lookupIds.filter((id) => this.chatRow(id)).map((id) => id)])];
      const storedNames = matches.filter((r) => r.display_name).map((r) => ({ name: r.display_name, source: r.display_name_source ?? "legacy" as IdentityNameSource }));
      const oldChatNames = canonicalMatches.map((jid) => this.chatRow(jid)).filter((r): r is ChatRow => Boolean(r?.display_name)).map((r) => ({ name: r.display_name, source: "legacy" as IdentityNameSource }));
      const currentName = resolveContactDisplayName({ candidates: [...storedNames, ...oldChatNames], username: matches.find((r) => r.username)?.username, phoneNumber: phoneNumberFromPnJid(matches.find((r) => r.phone_jid)?.phone_jid), fallbackJid: canonicalJid });
      const name = resolveContactDisplayName({ existingName: currentName.name, existingSource: currentName.source, candidates: observation.displayName ? [{ name: observation.displayName, source: observation.displayNameSource ?? "chat" }] : [], username, phoneNumber: phoneNumberFromPnJid(phoneJid), fallbackJid: canonicalJid });

      for (const oldCanonical of canonicalMatches) {
        if (oldCanonical === canonicalJid) continue;
        const oldChat = this.chatRow(oldCanonical);
        if (oldChat) {
          const keep = this.chatRow(canonicalJid);
          if (!keep) this.db.prepare("UPDATE chat_state SET jid=? WHERE jid=?").run(canonicalJid, oldCanonical);
          else {
            const mergedName = resolveContactDisplayName({ existingName: name.name, existingSource: name.source, candidates: [{ name: oldChat.display_name, source: "legacy" }], fallbackJid: canonicalJid });
            const updatedAt = oldChat.updated_at > keep.updated_at ? oldChat.updated_at : keep.updated_at;
            this.db.prepare("UPDATE chat_state SET archived=?,display_name=?,is_group=?,notification_policy=?,updated_at=? WHERE jid=?").run(Math.max(keep.archived, oldChat.archived), mergedName.name, Math.max(keep.is_group, oldChat.is_group), classifyNotificationPolicy(mergedName.name, this.nameRegex), updatedAt, canonicalJid);
            this.db.prepare("DELETE FROM chat_state WHERE jid=?").run(oldCanonical);
          }
          this.db.prepare(`INSERT INTO pending_notification(jid,message_count,first_message_at,last_message_at,last_message_preview)
            SELECT ?,p.message_count,p.first_message_at,p.last_message_at,p.last_message_preview FROM pending_notification p WHERE p.jid=? AND ?=1 AND EXISTS(SELECT 1 FROM chat_state target WHERE target.jid=? AND target.archived=1)
            ON CONFLICT(jid) DO UPDATE SET message_count=message_count+excluded.message_count,first_message_at=min(first_message_at,excluded.first_message_at),last_message_at=max(last_message_at,excluded.last_message_at),last_message_preview=CASE WHEN excluded.last_message_at>=last_message_at THEN excluded.last_message_preview ELSE last_message_preview END`).run(canonicalJid, oldCanonical, oldChat.archived, canonicalJid);
          this.db.prepare("DELETE FROM pending_notification WHERE jid=?").run(oldCanonical);
        }
      }
      if (matches.length) this.db.prepare(`DELETE FROM contact_identity WHERE canonical_jid IN (${matches.map(() => "?").join(",")})`).run(...matches.map((r) => r.canonical_jid));
      const mergedLid = lidJid ?? matches.find((r) => r.lid_jid)?.lid_jid ?? null;
      const mergedPhone = phoneJid ?? matches.find((r) => r.phone_jid)?.phone_jid ?? null;
      const mergedUsername = username ?? matches.find((r) => r.username)?.username ?? null;
      const mergedDisplayName = name.name === canonicalJid && name.source === null ? null : name.name;
      const mergedSource = name.source;
      this.db.prepare(`INSERT INTO contact_identity(canonical_jid,lid_jid,phone_jid,username,display_name,display_name_source,updated_at) VALUES(?,?,?,?,?,?,?)
        ON CONFLICT(canonical_jid) DO UPDATE SET lid_jid=COALESCE(excluded.lid_jid,lid_jid),phone_jid=COALESCE(excluded.phone_jid,phone_jid),username=COALESCE(excluded.username,username),display_name=COALESCE(excluded.display_name,display_name),display_name_source=COALESCE(excluded.display_name_source,display_name_source),updated_at=excluded.updated_at`)
        .run(canonicalJid, mergedLid, mergedPhone, mergedUsername, mergedDisplayName, mergedSource, now);
      const row = this.chatRow(canonicalJid);
      if (row && mergedDisplayName && row.display_name !== mergedDisplayName) this.refreshChatName(canonicalJid, mergedDisplayName);
    })();
  }

  resolveIdentity(jid: string): ContactIdentity {
    const identity = this.identityFor(jid);
    const canonicalJid = identity?.canonical_jid ?? jid;
    const chat = this.chatRow(canonicalJid);
    const phoneJid = pnJid(identity?.phone_jid) ?? pnJid(canonicalJid);
    const lidJid = identity?.lid_jid ?? (isLidJid(canonicalJid) ? canonicalJid : undefined);
    const username = identity?.username ?? usernameFromJid(jid);
    const best = resolveContactDisplayName({ existingName: identity?.display_name ?? chat?.display_name, existingSource: identity?.display_name_source, username, phoneNumber: phoneNumberFromPnJid(phoneJid), fallbackJid: canonicalJid });
    const output: ContactIdentity = { jid: canonicalJid, canonicalJid, displayName: best.name };
    if (phoneJid) { output.phoneJid = phoneJid; output.phoneNumber = phoneNumberFromPnJid(phoneJid); }
    if (lidJid) output.lidJid = lidJid;
    if (username) output.username = username;
    if (phoneJid && lidJid) output.remoteJidAlt = canonicalJid === lidJid ? phoneJid : lidJid;
    return output;
  }

  applyChat(chat: { jid: string; archived?: boolean; displayName?: string | null; isGroup?: boolean }, at = new Date().toISOString()): { oldPolicy?: NotificationPolicy; newPolicy: NotificationPolicy; nameChanged: boolean } {
    const canonicalJid = this.canonicalFor(chat.jid);
    const previous = this.chatRow(canonicalJid);
    const identity = this.identityFor(canonicalJid);
    const candidate = chat.displayName;
    const resolved = resolveContactDisplayName({ existingName: identity?.display_name ?? previous?.display_name, existingSource: identity?.display_name_source, candidates: candidate ? [{ name: candidate, source: "chat" }] : [], username: identity?.username ?? usernameFromJid(canonicalJid), phoneNumber: phoneNumberFromPnJid(identity?.phone_jid ?? canonicalJid), fallbackJid: canonicalJid });
    const displayName = resolved.name === canonicalJid && resolved.source === null ? null : resolved.name;
    const policy = classifyNotificationPolicy(displayName, this.nameRegex);
    this.db.prepare(`INSERT INTO chat_state(jid,archived,display_name,is_group,updated_at,notification_policy) VALUES(?,?,?,?,?,?)
      ON CONFLICT(jid) DO UPDATE SET archived=COALESCE(?,archived),display_name=COALESCE(?,display_name),is_group=COALESCE(?,is_group),updated_at=excluded.updated_at,notification_policy=?`)
      .run(canonicalJid, Number(chat.archived ?? false), displayName, Number(chat.isGroup ?? canonicalJid.endsWith("@g.us")), at, policy,
        chat.archived === undefined ? null : Number(chat.archived), displayName, chat.isGroup === undefined ? null : Number(chat.isGroup), policy);
    if (chat.archived === false) this.clearPendingForIdentity(canonicalJid);
    if (!canonicalJid.endsWith("@g.us")) this.observeIdentity({ preferredJid: canonicalJid, lidJid: isLidJid(canonicalJid) ? canonicalJid : undefined, phoneJid: pnJid(canonicalJid), username: usernameFromJid(canonicalJid), displayName: candidate, displayNameSource: "chat" });
    return { oldPolicy: previous?.notification_policy, newPolicy: policy, nameChanged: previous?.display_name !== displayName };
  }
  applyChats(chats: Array<{ id?: string; jid?: string; archived?: boolean; name?: string; subject?: string; isGroup?: boolean }>, at?: string): void {
    for (const c of chats) {
      const jid = c.id ?? c.jid;
      if (!jid) continue;
      const group = c.isGroup ?? jid.endsWith("@g.us");
      this.applyChat({ jid, archived: c.archived, displayName: group ? c.subject ?? c.name : c.name ?? c.subject, isGroup: group }, at);
    }
  }
  updateContact(jid: string, name?: string | null): { oldPolicy?: NotificationPolicy; newPolicy?: NotificationPolicy; nameChanged: boolean } | undefined {
    if (!name) return;
    const old = this.getChat(jid);
    this.observeIdentity({ preferredJid: jid, lidJid: isLidJid(jid) ? jid : undefined, phoneJid: pnJid(jid), username: usernameFromJid(jid), displayName: name, displayNameSource: "contact_name" });
    const current = this.getChat(jid);
    const resolved = this.resolveIdentity(jid);
    return { oldPolicy: old?.notificationPolicy, newPolicy: current?.notificationPolicy, nameChanged: old?.displayName !== resolved.displayName };
  }
  deleteChat(jid: string): void { const canonical = this.canonicalFor(jid); this.db.transaction(() => { this.db.prepare("DELETE FROM chat_state WHERE jid=?").run(canonical); this.db.prepare("DELETE FROM pending_notification WHERE jid=?").run(canonical); })(); }
  getChat(jid: string): Chat | undefined {
    const row = this.chatRow(this.canonicalFor(jid));
    return row ? { jid: row.jid, archived: row.archived, displayName: row.display_name, isGroup: row.is_group, updatedAt: row.updated_at, notificationPolicy: row.notification_policy } : undefined;
  }
  private clearPendingForIdentity(canonicalJid: string): void {
    const identity = this.identityFor(canonicalJid);
    const aliases = [canonicalJid, identity?.lid_jid, identity?.phone_jid].filter((value): value is string => Boolean(value));
    this.db.prepare(`DELETE FROM pending_notification WHERE jid IN (${aliases.map(() => "?").join(",")})`).run(...aliases);
  }
  recordIncoming(jid: string, identity: string, at = new Date().toISOString(), preview: string | null = null): boolean {
    const canonicalJid = this.canonicalFor(jid);
    const hash = createHash("sha256").update(identity).digest("hex");
    return this.db.transaction(() => {
      if (!(this.db.prepare("SELECT 1 FROM chat_state WHERE jid=? AND archived=1").get(canonicalJid))) return false;
      if (!this.db.prepare("INSERT OR IGNORE INTO processed_message(message_hash,seen_at) VALUES(?,?)").run(hash, at).changes) return false;
      this.db.prepare(`INSERT INTO pending_notification(jid,message_count,first_message_at,last_message_at,last_message_preview) VALUES(?,1,?,?,?)
        ON CONFLICT(jid) DO UPDATE SET message_count=message_count+1,last_message_at=excluded.last_message_at,last_message_preview=excluded.last_message_preview`).run(canonicalJid, at, at, preview);
      return true;
    })();
  }
  getPending(limit = 50): Pending[] {
    const rows = this.db.prepare("SELECT p.jid,p.message_count AS messageCount,p.first_message_at AS firstMessageAt,p.last_message_at AS lastMessageAt,p.last_message_preview AS lastMessagePreview,c.is_group AS isGroup,c.notification_policy AS notificationPolicy FROM pending_notification p JOIN chat_state c USING(jid) WHERE c.archived=1 ORDER BY p.first_message_at LIMIT ?").all(limit) as Array<{ jid: string; messageCount: number; firstMessageAt: string; lastMessageAt: string; lastMessagePreview: string | null; isGroup: number; notificationPolicy: NotificationPolicy }>;
    return rows.map((row) => ({ ...this.resolveIdentity(row.jid), ...row, isGroup: Boolean(row.isGroup) }));
  }
  getArchivedPending(chats: Pending[]): Pending[] {
    const archived = new Set((this.db.prepare(`SELECT jid FROM chat_state WHERE archived=1 AND jid IN (${chats.map(() => "?").join(",") || "NULL"})`).all(...chats.map((chat) => chat.jid)) as Array<{ jid: string }>).map((row) => row.jid));
    return chats.filter((chat) => archived.has(chat.jid));
  }
  clearIfUnchanged(snapshots: Snapshot[]): number { return this.db.transaction(() => { const stmt = this.db.prepare("DELETE FROM pending_notification WHERE jid=? AND last_message_at=? AND EXISTS(SELECT 1 FROM chat_state WHERE chat_state.jid=pending_notification.jid AND archived=1)"); let n = 0; for (const s of snapshots) n += stmt.run(s.jid, s.lastMessageAt).changes; return n; })(); }
  countArchived(): number { return (this.db.prepare("SELECT COUNT(*) AS n FROM chat_state WHERE archived=1").get() as { n: number }).n; }
  identityCount(): number { return (this.db.prepare("SELECT COUNT(*) AS n FROM contact_identity").get() as { n: number }).n; }
  lidsForLocalBackfill(): string[] { return (this.db.prepare("SELECT DISTINCT lid_jid AS jid FROM contact_identity WHERE lid_jid IS NOT NULL UNION SELECT jid FROM chat_state WHERE is_group=0 AND jid LIKE '%@lid'").all() as Array<{ jid: string }>).map((x) => x.jid); }
  pruneDeduplication(olderThan = new Date(Date.now() - 7 * 86400_000).toISOString()): number { return this.db.prepare("DELETE FROM processed_message WHERE seen_at < ?").run(olderThan).changes; }
  healthcheck(): boolean { return this.db.prepare("SELECT 1").get() !== undefined; }
  close(): void { if (this.db.open) this.db.close(); }
}
