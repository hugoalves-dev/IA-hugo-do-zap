export interface Config {
  timezone: string; checkCron: string; workDays: number[]; workWindows: Array<{ start: string; end: string }>;
  queueOutsideWorkHours: boolean; includeGroups: boolean; notificationMaxChats: number; workOnlyNameRegex: RegExp; neverNotifyGroupNames: string[];
  webhookUrl: string; webhookSecret: string; dataDir: string; logLevel: string;
}

export function loadConfig(env = process.env): Config {
  const webhookUrl = (env.N8N_WEBHOOK_URL ?? "").trim();
  const webhookSecret = env.N8N_WEBHOOK_SECRET ?? "";
  if (!webhookUrl) throw new Error("N8N_WEBHOOK_URL is required");
  if (!webhookSecret) throw new Error("N8N_WEBHOOK_SECRET is required");
  const parsedUrl = new URL(webhookUrl);
  if (parsedUrl.protocol !== "https:" && parsedUrl.hostname !== "n8n") throw new Error("N8N_WEBHOOK_URL must use HTTPS except for internal n8n networking");
  const workDays = (env.WORK_DAYS || "1,2,3,4,5").split(",").map((v) => Number(v.trim()));
  if (workDays.some((v) => !Number.isInteger(v) || v < 1 || v > 7)) throw new Error("WORK_DAYS must contain ISO weekdays 1-7");
  const workWindows = (env.WORK_WINDOWS || "08:00-12:00,13:30-17:30").split(",").map((value) => {
    const match = /^(\d{2}:\d{2})-(\d{2}:\d{2})$/.exec(value.trim());
    if (!match || [match[1], match[2]].some((t) => { const [h, m] = t.split(":").map(Number); return h > 23 || m > 59; })) throw new Error("WORK_WINDOWS must use HH:MM-HH:MM intervals");
    return { start: match[1], end: match[2] };
  });
  const regexSource = env.WORK_ONLY_NAME_REGEX ?? "(^|[^A-Z0-9])JC([^A-Z0-9]|$)";
  let workOnlyNameRegex: RegExp;
  try { workOnlyNameRegex = new RegExp(regexSource, "i"); }
  catch (error) { throw new Error(`WORK_ONLY_NAME_REGEX is invalid: ${error instanceof Error ? error.message : "invalid regular expression"}`); }
  const neverNotifyGroupNames = (env.NEVER_NOTIFY_GROUP_NAMES ?? "233/234").split(",").map(normalizeDisplayName).filter(Boolean);
  return {
    timezone: env.TZ || "America/Sao_Paulo", checkCron: env.CHECK_CRON || "* * * * *", workDays, workWindows,
    queueOutsideWorkHours: env.QUEUE_OUTSIDE_WORK_HOURS === "true", includeGroups: env.INCLUDE_GROUPS !== "false",
    notificationMaxChats: Math.max(1, Number.parseInt(env.NOTIFICATION_MAX_CHATS || "50", 10) || 50), workOnlyNameRegex, neverNotifyGroupNames,
    webhookUrl, webhookSecret, dataDir: env.DATA_DIR || "/app/data", logLevel: env.LOG_LEVEL || "info",
  };
}

export type NotificationPolicy = "WORK_ONLY" | "ANYTIME";
export function normalizeDisplayName(name: string | null | undefined): string {
  return (name ?? "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").trim().replace(/\s+/g, " ").toUpperCase();
}
export function isNeverNotifyGroup(name: string | null | undefined, isGroup: boolean, blockedNames: string[]): boolean {
  if (!isGroup) return false;
  const normalized = normalizeDisplayName(name);
  return Boolean(normalized) && blockedNames.some((blocked) => normalizeDisplayName(blocked) === normalized);
}
const defaultWorkOnlyRegex = /(^|[^A-Z0-9])JC([^A-Z0-9]|$)/i;
export function classifyNotificationPolicy(name: string | null | undefined, regex: RegExp = defaultWorkOnlyRegex): NotificationPolicy {
  regex.lastIndex = 0;
  const normalized = normalizeDisplayName(name);
  // JIDs and phone-like fallbacks must never drive name-based routing.
  if (!normalized || /[@+\d]/.test(normalized)) return "ANYTIME";
  return regex.test(normalized) ? "WORK_ONLY" : "ANYTIME";
}

export function isWithinWorkHours(date: Date, config: Pick<Config, "timezone" | "workDays" | "workWindows">): boolean {
  const parts = new Intl.DateTimeFormat("en-US", { timeZone: config.timezone, weekday: "short", hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).formatToParts(date);
  const values = Object.fromEntries(parts.map((p) => [p.type, p.value]));
  const day = ({ Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 } as Record<string, number>)[values.weekday];
  const time = `${values.hour}:${values.minute}`;
  return config.workDays.includes(day) && config.workWindows.some(({ start, end }) => time >= start && time < end);
}

export function localIso(date: Date, timezone: string): string {
  const parts = new Intl.DateTimeFormat("sv-SE", { timeZone: timezone, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23" }).formatToParts(date);
  const v = Object.fromEntries(parts.map((p) => [p.type, p.value]));
  const local = `${v.year}-${v.month}-${v.day}T${v.hour}:${v.minute}:${v.second}`;
  const asUtc = Date.UTC(+v.year, +v.month - 1, +v.day, +v.hour, +v.minute, +v.second);
  const offset = Math.round((asUtc - date.getTime()) / 60000);
  return `${local}${offset < 0 ? "-" : "+"}${String(Math.floor(Math.abs(offset) / 60)).padStart(2, "0")}:${String(Math.abs(offset) % 60).padStart(2, "0")}`;
}
