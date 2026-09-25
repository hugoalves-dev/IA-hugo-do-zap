import pino from "pino";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { loadConfig } from "./config.js";
import { Notifier } from "./notifier.js";
import { WatcherState } from "./watcher-state.js";

const config = loadConfig();
const dir = mkdtempSync(join(tmpdir(), "wa-notification-test-"));
const state = new WatcherState(dir);
const jid = "notification-test@s.whatsapp.net";
state.applyChat({ jid, archived: true, displayName: "Synthetic test" });
state.recordIncoming(jid, `synthetic-${Date.now()}`);
try {
  const notifier = new Notifier(config, state, pino({ level: config.logLevel }));
  await notifier.notifyPending();
} finally { state.close(); rmSync(dir, { recursive: true, force: true }); }
