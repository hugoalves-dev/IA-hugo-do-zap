export interface IncomingMessage {
  key: { id?: string | null; fromMe?: boolean | null; remoteJid?: string | null; participant?: string | null };
  message?: Record<string, unknown> | null;
}
const ignoredContent = new Set(["protocolMessage", "senderKeyDistributionMessage", "messageContextInfo", "reactionMessage", "pollUpdateMessage"]);
export function isUserContent(message: IncomingMessage): boolean { return Object.keys(message.message ?? {}).some((key) => !ignoredContent.has(key)); }
export function isEligibleMessage(message: IncomingMessage, includeGroups: boolean): boolean {
  const jid = message.key.remoteJid ?? "";
  return message.key.fromMe !== true && Boolean(message.key.id) && Boolean(jid) && jid !== "status@broadcast" && !jid.endsWith("@broadcast") && !jid.endsWith("@newsletter") && (!jid.endsWith("@g.us") || includeGroups) && (jid.includes("@") || jid.endsWith("@lid")) && isUserContent(message);
}
export function messageIdentity(message: IncomingMessage): string { return `${message.key.remoteJid ?? ""}\n${message.key.id ?? ""}\n${message.key.participant ?? ""}`; }
export function shouldProcessUpsert(type: string): boolean { return type === "notify"; }
