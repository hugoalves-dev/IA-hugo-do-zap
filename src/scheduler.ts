import cron from "node-cron";
import type { ScheduledTask } from "node-cron";
import type { Config } from "./config.js";
import type { Notifier } from "./notifier.js";
import type { WatcherState } from "./watcher-state.js";

export function startScheduler(config: Config, notifier: Notifier, state: WatcherState, log: { info: (o: object, m?: string) => void; error: (o: object, m?: string) => void }): ScheduledTask {
  let running = false;
  return cron.schedule(config.checkCron, () => {
    if (running) return;
    running = true;
    log.info({ event: "notification_schedule_triggered", timezone: config.timezone }, "Notification flush triggered");
    void notifier.notifyPending()
      .catch((error: unknown) => log.error({ error: error instanceof Error ? error.name : "UnknownError" }, "Scheduled notification failed"))
      .finally(() => { state.pruneDeduplication(); running = false; });
  }, { timezone: config.timezone, noOverlap: true });
}
