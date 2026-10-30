package dev.hugo.watcher;

/** Sends one user-facing message to the configured private Telegram chat. */
public interface TelegramTransport {
    TelegramDeliveryResult send(String text);
    default TelegramDeliveryResult sendHtml(String html) { return send(html); }
    default TelegramDeliveryResult sendAdmin(String text, String payload) { return send(text); }
    default TelegramDeliveryResult editAdmin(String text, String payload) { return sendAdmin(text, payload); }
}
