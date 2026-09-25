import { existsSync } from "node:fs";
import Database from "better-sqlite3";

try {
  const path = `${process.env.DATA_DIR || "/app/data"}/watcher.db`;
  if (!existsSync(path)) process.exit(1);
  const db = new Database(path, { readonly: true, fileMustExist: true });
  const ok = db.prepare("SELECT 1 FROM watcher_state LIMIT 1").get() !== undefined;
  db.close();
  process.exit(ok ? 0 : 1);
} catch { process.exit(1); }
