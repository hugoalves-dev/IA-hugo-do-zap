import pino from "pino";
import { mkdirSync, chmodSync } from "node:fs";
import { loadConfig } from "./config.js";
import { Notifier } from "./notifier.js";
import { startScheduler } from "./scheduler.js";
import { WatcherState } from "./watcher-state.js";
import { WhatsAppWatcher } from "./whatsapp.js";

const config = loadConfig();
const log = pino({ level: config.logLevel });
process.umask(0o077);
mkdirSync(`${config.dataDir}/auth`, { recursive: true, mode: 0o700 });
chmodSync(`${config.dataDir}/auth`, 0o700);
const state = new WatcherState(config.dataDir, undefined, config.workOnlyNameRegex);
const notifier = new Notifier(config, state, log);
const scheduler = startScheduler(config, notifier, state, log);
const whatsapp = new WhatsAppWatcher(config, state, log);
log.info({ event: "application_started", timezone: config.timezone, checkCron: config.checkCron, includeGroups: config.includeGroups, workDays: config.workDays, workWindows: config.workWindows, workOnlyNameRegex: config.workOnlyNameRegex.source }, "Watcher started");

let stopping = false;
async function shutdown(signal: string): Promise<void> {
  if (stopping) return;
  stopping = true;
  log.info({ event: "application_stopping", signal }, "Stopping watcher");
  scheduler.stop();
  await whatsapp.stop();
  state.close();
  process.exit(0);
}
process.once("SIGTERM", () => { void shutdown("SIGTERM"); });
process.once("SIGINT", () => { void shutdown("SIGINT"); });
void whatsapp.start().catch((error: unknown) => {
  log.error({ error: error instanceof Error ? error.name : "UnknownError" }, "WhatsApp startup failed");
  process.exitCode = 1;
});
