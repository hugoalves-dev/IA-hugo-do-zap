package dev.hugo.watcher;

import java.time.Duration;

/** Result of one Bot API send attempt, including Telegram rate-limit metadata. */
public record TelegramDeliveryResult(boolean delivered, boolean rateLimited, Duration retryAfter) {
    public TelegramDeliveryResult {
        if (delivered && (rateLimited || retryAfter != null)) {
            throw new IllegalArgumentException("successful delivery cannot have retry metadata");
        }
        if (retryAfter != null && (retryAfter.isZero() || retryAfter.isNegative())) retryAfter = null;
        if (retryAfter != null) rateLimited = true;
    }

    public static TelegramDeliveryResult success() { return new TelegramDeliveryResult(true, false, null); }
    public static TelegramDeliveryResult failure() { return new TelegramDeliveryResult(false, false, null); }
    public static TelegramDeliveryResult failure(Duration retryAfter) {
        return retryAfter == null ? failure() : rateLimited(retryAfter);
    }
    public static TelegramDeliveryResult rateLimited(Duration retryAfter) {
        return new TelegramDeliveryResult(false, true, retryAfter);
    }
}
