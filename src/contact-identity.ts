export type IdentityNameSource = "contact_name" | "notify" | "verified" | "chat" | "username" | "phone" | "legacy";
export interface IdentityNameCandidate { name?: string | null; source: IdentityNameSource }
export interface DisplayNameInput { existingName?: string | null; existingSource?: IdentityNameSource | null; candidates?: IdentityNameCandidate[]; username?: string | null; phoneNumber?: string | null; fallbackJid: string }
const sourceRank: Record<IdentityNameSource, number> = { contact_name: 6, notify: 5, verified: 4, chat: 3, legacy: 3, username: 2, phone: 1 };

export function isLidJid(value: string | null | undefined): value is string { return typeof value === "string" && /^.+@lid$/i.test(value); }
export function phoneNumberFromPnJid(value: string | null | undefined): string | undefined {
  if (!value || !/^\d{1,15}(?::\d+)?@s\.whatsapp\.net$/i.test(value)) return undefined;
  const number = value.slice(0, value.indexOf("@")).split(":", 1)[0];
  return number.length >= 1 && number.length <= 15 ? number : undefined;
}
export function pnJid(value: string | null | undefined): string | undefined { return phoneNumberFromPnJid(value) ? value! : undefined; }
export function cleanUsername(value: string | null | undefined): string | undefined {
  if (!value) return undefined;
  const username = value.trim().replace(/^@/, "").replace(/@+$/, "");
  return /^[\p{L}\p{N}._-]{1,64}$/u.test(username) ? username : undefined;
}
export function usernameFromJid(value: string | null | undefined): string | undefined {
  if (!value || !value.startsWith("@")) return undefined;
  return cleanUsername(value);
}
export function isTechnicalDisplayName(value: string | null | undefined): boolean {
  if (!value?.trim()) return true;
  const name = value.trim();
  return /^\+?\d+(?::\d+)?@(lid|s\.whatsapp\.net|g\.us|broadcast|hosted\.lid|hosted)$/i.test(name)
    || /^\d{7,}$/.test(name)
    || /^@[\p{L}\p{N}._-]+$/u.test(name)
    || /@(lid|s\.whatsapp\.net|g\.us|broadcast|hosted\.lid|hosted)$/i.test(name);
}
function validName(name: string | null | undefined): string | undefined {
  const trimmed = name?.trim();
  return trimmed && !isTechnicalDisplayName(trimmed) ? trimmed : undefined;
}
export function resolveContactDisplayName(input: DisplayNameInput): { name: string; source: IdentityNameSource | null } {
  const existingName = validName(input.existingName);
  const existingSource = input.existingSource ?? (existingName ? "legacy" : null);
  let selectedName = existingName;
  let selectedSource = existingSource;
  for (const candidate of input.candidates ?? []) {
    const name = validName(candidate.name);
    if (!name) continue;
    if (!selectedName || sourceRank[candidate.source] >= sourceRank[selectedSource ?? "legacy"]) {
      selectedName = name;
      selectedSource = candidate.source;
    }
  }
  if (selectedName) return { name: selectedName, source: selectedSource };
  const username = cleanUsername(input.username);
  if (username) return { name: username, source: "username" };
  if (input.phoneNumber) return { name: `+${input.phoneNumber}`, source: "phone" };
  return { name: input.fallbackJid, source: null };
}
