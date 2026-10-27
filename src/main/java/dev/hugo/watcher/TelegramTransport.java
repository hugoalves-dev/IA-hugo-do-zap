package dev.hugo.watcher;

/** Sends one user-facing message to the configured private Telegram chat. */
public interface TelegramTransport {
    boolean send(String text);
}
