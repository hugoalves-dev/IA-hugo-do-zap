package dev.hugo.watcher.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;

/** All paid classification gates are opt-in. No credential or cost is defaulted. */
@ConfigurationProperties("watcher.ai")
public record AiClassificationProperties(boolean enabled, String geminiApiKey, String geminiModel,
                                         String openRouterApiKey, String openRouterModel,
                                         long attemptHoldMicros, Duration timeout, String ownJid,
                                         boolean classificationEnabled, boolean disableLocalBudget,
                                         @DefaultValue("true") boolean requireChatConsent,
                                         boolean allowUnbudgetedGeminiFallback) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public AiClassificationProperties { }
    public AiClassificationProperties(boolean enabled, String geminiApiKey, String geminiModel,
                                      String openRouterApiKey, String openRouterModel, long attemptHoldMicros,
                                      Duration timeout, String ownJid) {
        this(enabled, geminiApiKey, geminiModel, openRouterApiKey, openRouterModel, attemptHoldMicros, timeout, ownJid, false, false, true, false);
    }
    @Override public String toString() { return "AiClassificationProperties[credentials=REDACTED,enabled=" + enabled + "]"; }
    public boolean configured() {
        return enabled && classificationEnabled && openRouterApiKey != null && !openRouterApiKey.isBlank()
                && openRouterModel != null && openRouterModel.matches("[A-Za-z0-9._/-]+")
                && (disableLocalBudget || attemptHoldMicros > 0) && timeout != null && !timeout.isNegative() && !timeout.isZero()
                && timeout.compareTo(Duration.ofSeconds(30)) <= 0;
    }
    public boolean fallbackConfigured() {
        return (!disableLocalBudget || allowUnbudgetedGeminiFallback) && geminiApiKey != null && !geminiApiKey.isBlank()
                && geminiModel != null && geminiModel.matches("[A-Za-z0-9._-]+");
    }
}
