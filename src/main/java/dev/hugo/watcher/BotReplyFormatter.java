package dev.hugo.watcher;

/**
 * Identifies text authored by the bot at channel delivery boundaries.
 * The legacy template disclosure stays in storage to preserve V17 approval rules.
 */
public final class BotReplyFormatter {
    public static final String PREFIX = "[🤖 HUGO ZAP BOT]: ";
    private static final String LEGACY_DISCLOSURE = "Resposta automática:";

    private BotReplyFormatter() { }

    /** Plain-text Telegram admin replies (never applied to notification events). */
    public static String telegramAdmin(String text) {
        String value = text == null ? "" : text;
        return value.startsWith(PREFIX) ? value : PREFIX + value;
    }

    /**
     * Convert an approved legacy template into a uniquely prefixed WhatsApp reply.
     * Return null if the stored template lacks the mandatory disclosure or a message.
     * Authorizations and runtime gates must be checked by the caller before sending.
     */
    public static String whatsAppTemplate(String approvedBody) {
        if (approvedBody == null) return null;
        String value = approvedBody.stripLeading();
        if (!value.regionMatches(true, 0, LEGACY_DISCLOSURE, 0, LEGACY_DISCLOSURE.length()))
            return null;
        String message = value.substring(LEGACY_DISCLOSURE.length()).stripLeading();
        if (message.startsWith(PREFIX)) message = message.substring(PREFIX.length());
        if (message.isBlank()) return null;
        return PREFIX + message;
    }
}
