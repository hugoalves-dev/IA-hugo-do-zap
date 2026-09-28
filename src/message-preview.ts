const wrappers = ["ephemeralMessage", "viewOnceMessage", "viewOnceMessageV2", "viewOnceMessageV2Extension", "documentWithCaptionMessage"];
const fallbacks: Record<string, string> = {
  imageMessage: "[Image]", videoMessage: "[Video]", audioMessage: "[Audio]", stickerMessage: "[Sticker]",
  contactMessage: "[Contact]", contactsArrayMessage: "[Contact]", locationMessage: "[Location]",
  liveLocationMessage: "[Location]", documentMessage: "[Document]",
};

function shorten(value: string): string {
  const normalized = value.replace(/\s+/gu, " ").trim();
  return Array.from(normalized).slice(0, 140).join("");
}

export function extractMessagePreview(content: Record<string, unknown> | null | undefined): string {
  let current: Record<string, unknown> = content ?? {};
  for (let depth = 0; depth < 5; depth++) {
    const text = typeof current.conversation === "string" ? current.conversation
      : typeof (current.extendedTextMessage as any)?.text === "string" ? (current.extendedTextMessage as any).text
      : undefined;
    if (text !== undefined) return shorten(text);

    for (const key of ["imageMessage", "videoMessage", "documentMessage"]) {
      const caption = (current[key] as any)?.caption;
      if (typeof caption === "string" && caption.trim()) return shorten(caption);
    }
    if ((current.audioMessage as any)?.ptt === true) return "[Voice message]";
    for (const [key, fallback] of Object.entries(fallbacks)) if (current[key] !== undefined) return fallback;

    const wrapper = wrappers.find((key) => current[key] && typeof current[key] === "object");
    if (!wrapper) return "[Message]";
    const nested = (current[wrapper] as any).message;
    if (!nested || typeof nested !== "object") return "[Message]";
    current = nested as Record<string, unknown>;
  }
  return "[Message]";
}
