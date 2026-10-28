package dev.hugo.watcher.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/** All paid classification gates are opt-in. No credential or cost is defaulted. */
@ConfigurationProperties("watcher.ai")
public record AiClassificationProperties(boolean enabled, String geminiApiKey, String geminiModel,
                                         String openRouterApiKey, String openRouterModel,
                                         long attemptHoldMicros, Duration timeout, String ownJid) {
    @Override public String toString() { return "AiClassificationProperties[credentials=REDACTED,enabled=" + enabled + "]"; }
    public boolean configured() {
        return enabled && geminiApiKey != null && !geminiApiKey.isBlank()
                && geminiModel != null && geminiModel.matches("[A-Za-z0-9._-]+")
                && attemptHoldMicros > 0 && timeout != null && !timeout.isNegative() && !timeout.isZero()
                && timeout.compareTo(Duration.ofSeconds(30)) <= 0;
    }
    public boolean fallbackConfigured() {
        return openRouterApiKey != null && !openRouterApiKey.isBlank()
                && openRouterModel != null && openRouterModel.matches("[A-Za-z0-9._/-]+");
    }
}
