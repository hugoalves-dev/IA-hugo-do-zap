import Database from "better-sqlite3";
import { createHash } from "node:crypto";
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { classifyNotificationPolicy, type NotificationPolicy } from "./config.js";

export interface Chat { jid: string; archived: number; displayName: string | null; isGroup: number; updatedAt: string; notificationPolicy: NotificationPolicy }
export interface Pending { jid: string; messageCount: number; firstMessageAt: string; lastMessageAt: string; displayName: string | null; isGroup: boolean; notificationPolicy: NotificationPolicy }
export type Snapshot = Pick<Pending, "jid" | "messageCount" | "firstMessageAt" | "lastMessageAt">;

export class WatcherState {
  private readonly db: Database.Database;
  constructor(dataDir: string, _unusedTarget?: string, private readonly nameRegex: RegExp = /(^|[^A-Z0-9])JC([^A-Z0-9]|$)/i) {
    mkdirSync(dataDir, { recursive: true, mode: 0o700 });
    this.db = new Database(join(dataDir, "watcher.db"));
    this.db.pragma("journal_mode = WAL");
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS watcher_state (contact_phone TEXT PRIMARY KEY, pending INTEGER NOT NULL DEFAULT 0, message_count INTEGER NOT NULL DEFAULT 0, first_message_at TEXT NULL, last_message_at TEXT NULL, last_notified_at TEXT NULL);
      CREATE TABLE IF NOT EXISTS chat_state (jid TEXT PRIMARY KEY, archived INTEGER NOT NULL DEFAULT 0, display_name TEXT NULL, is_group INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS pending_notification (jid TEXT PRIMARY KEY, message_count INTEGER NOT NULL DEFAULT 0, first_message_at TEXT NOT NULL, last_message_at TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS processed_message (message_hash TEXT PRIMARY KEY, seen_at TEXT NOT NULL);
    `);
    const columns = this.db.pragma("table_info(chat_state)") as Array<{ name: string }>;
    if (!columns.some((column) => column.name === "notification_policy")) this.db.exec("ALTER TABLE chat_state ADD COLUMN notification_policy TEXT NOT NULL DEFAULT 'ANYTIME'");
    this.db.exec("UPDATE chat_state SET notification_policy='ANYTIME' WHERE notification_policy NOT IN ('ANYTIME','WORK_ONLY')");
    this.db.prepare("UPDATE chat_state SET notification_policy=? WHERE 1=1").run("ANYTIME");
    this.db.transaction(() => {
      const rows = this.db.prepare("SELECT jid,display_name FROM chat_state").all() as Array<{ jid: string; display_name: string | null }>;
      const stmt = this.db.prepare("UPDATE chat_state SET notification_policy=? WHERE jid=?");
      for (const row of rows) stmt.run(classifyNotificationPolicy(row.display_name, this.nameRegex), row.jid);
    })();
  }
  applyChat(chat: { jid: string; archived?: boolean; displayName?: string | null; isGroup?: boolean }, at = new Date().toISOString()): { oldPolicy?: NotificationPolicy; newPolicy: NotificationPolicy; nameChanged: boolean } {
    const previous = this.getChat(chat.jid);
    const displayName = chat.displayName ?? previous?.displayName ?? null;
    const notificationPolicy = classifyNotificationPolicy(displayName, this.nameRegex);
    this.db.prepare(`INSERT INTO chat_state(jid, archived, display_name, is_group, updated_at, notification_policy) VALUES(?, ?, ?, ?, ?, ?)
      ON CONFLICT(jid) DO UPDATE SET archived=COALESCE(?, archived), display_name=COALESCE(?, display_name), is_group=COALESCE(?, is_group), updated_at=excluded.updated_at, notification_policy=?`)
      .run(chat.jid, Number(chat.archived ?? false), chat.displayName ?? null, Number(chat.isGroup ?? chat.jid.endsWith("@g.us")), at,
        notificationPolicy, chat.archived === undefined ? null : Number(chat.archived), chat.displayName ?? null, chat.isGroup === undefined ? null : Number(chat.isGroup), notificationPolicy);
    return { oldPolicy: previous?.notificationPolicy, newPolicy: notificationPolicy, nameChanged: previous?.displayName !== displayName };
  }
  applyChats(chats: Array<{ id?: string; jid?: string; archived?: boolean; name?: string; subject?: string; isGroup?: boolean }>, at?: string): void {
    for (const c of chats) { const jid = c.id ?? c.jid; if (jid) this.applyChat({ jid, archived: c.archived, displayName: c.name ?? c.subject, isGroup: c.isGroup }, at); }
  }
  updateContact(jid: string, name?: string | null): { oldPolicy?: NotificationPolicy; newPolicy?: NotificationPolicy; nameChanged: boolean } | undefined {
    if (!name) return;
    const old = this.getChat(jid); if (!old) return;
    const newPolicy = classifyNotificationPolicy(name, this.nameRegex);
    this.db.prepare("UPDATE chat_state SET display_name=?, updated_at=?, notification_policy=? WHERE jid=?").run(name, new Date().toISOString(), newPolicy, jid);
    return { oldPolicy: old.notificationPolicy, newPolicy, nameChanged: old.displayName !== name };
  }
  deleteChat(jid: string): void { this.db.transaction(() => { this.db.prepare("DELETE FROM chat_state WHERE jid=?").run(jid); this.db.prepare("DELETE FROM pending_notification WHERE jid=?").run(jid); })(); }
  getChat(jid: string): Chat | undefined { return this.db.prepare("SELECT jid, archived, display_name AS displayName, is_group AS isGroup, updated_at AS updatedAt, notification_policy AS notificationPolicy FROM chat_state WHERE jid=?").get(jid) as Chat | undefined; }
  recordIncoming(jid: string, identity: string, at = new Date().toISOString()): boolean {
    const hash = createHash("sha256").update(identity).digest("hex");
    return this.db.transaction(() => {
      if (!this.db.prepare("INSERT OR IGNORE INTO processed_message(message_hash, seen_at) VALUES(?, ?)").run(hash, at).changes) return false;
      this.db.prepare(`INSERT INTO pending_notification(jid,message_count,first_message_at,last_message_at) VALUES(?,1,?,?)
        ON CONFLICT(jid) DO UPDATE SET message_count=message_count+1,last_message_at=excluded.last_message_at`).run(jid, at, at);
      return true;
    })();
  }
  getPending(limit = 50): Pending[] { return this.db.prepare(`SELECT p.jid,p.message_count AS messageCount,p.first_message_at AS firstMessageAt,p.last_message_at AS lastMessageAt,COALESCE(c.display_name,p.jid) AS displayName,c.is_group AS isGroup,c.notification_policy AS notificationPolicy FROM pending_notification p JOIN chat_state c USING(jid) ORDER BY p.first_message_at LIMIT ?`).all(limit).map((x: any) => ({ ...x, isGroup: Boolean(x.isGroup) })) as Pending[]; }
  clearIfUnchanged(snapshots: Snapshot[]): number {
    return this.db.transaction(() => { const stmt = this.db.prepare("DELETE FROM pending_notification WHERE jid=? AND last_message_at=?"); let n = 0; for (const s of snapshots) n += stmt.run(s.jid, s.lastMessageAt).changes; return n; })();
  }
  countArchived(): number { return (this.db.prepare("SELECT COUNT(*) AS n FROM chat_state WHERE archived=1").get() as { n: number }).n; }
  pruneDeduplication(olderThan = new Date(Date.now() - 7 * 86400_000).toISOString()): number { return this.db.prepare("DELETE FROM processed_message WHERE seen_at < ?").run(olderThan).changes; }
  healthcheck(): boolean { return this.db.prepare("SELECT 1").get() !== undefined; }
  close(): void { if (this.db.open) this.db.close(); }
}
