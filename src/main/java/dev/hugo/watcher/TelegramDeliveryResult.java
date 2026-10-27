package dev.hugo.watcher;

import java.time.Duration;

/** Result of one Bot API send attempt; retryAfter is supplied only by Telegram's 429 response. */
public record TelegramDeliveryResult(boolean delivered, Duration retryAfter) {
    public TelegramDeliveryResult {
        if (delivered && retryAfter != null) throw new IllegalArgumentException("successful delivery cannot have retryAfter");
        if (retryAfter != null && (retryAfter.isZero() || retryAfter.isNegative())) retryAfter = null;
    }

    public static TelegramDeliveryResult success() { return new TelegramDeliveryResult(true, null); }
    public static TelegramDeliveryResult failure() { return new TelegramDeliveryResult(false, null); }
    public static TelegramDeliveryResult failure(Duration retryAfter) { return new TelegramDeliveryResult(false, retryAfter); }
}
