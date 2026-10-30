package dev.hugo.watcher.telegram;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("watcher.telegram.admin")
public class TelegramAdminProperties {
    private boolean pollingEnabled;
    private boolean webhookEnabled;
    private String webhookSecret;
    private String miniAppUrl;
    @Positive
    private Long userId;

    public boolean isPollingEnabled() { return pollingEnabled; }
    public void setPollingEnabled(boolean pollingEnabled) { this.pollingEnabled = pollingEnabled; }
    public boolean isWebhookEnabled() { return webhookEnabled; }
    public void setWebhookEnabled(boolean value) { webhookEnabled = value; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String value) { webhookSecret = value; }
    public String getMiniAppUrl() { return miniAppUrl; }
    public void setMiniAppUrl(String value) { miniAppUrl = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    @AssertTrue(message = "Telegram polling and webhook cannot both be enabled")
    public boolean isSingleConsumerValid() { return !(pollingEnabled && webhookEnabled); }

    @AssertTrue(message = "Telegram webhook requires a strong 32-256-character secret_token")
    public boolean isWebhookSecretValid() { return !webhookEnabled || webhookSecret != null && webhookSecret.matches("[A-Za-z0-9_-]{32,256}"); }

    @AssertTrue(message = "Telegram webhook requires an authorized admin user ID")
    public boolean isWebhookIdentityValid() { return !webhookEnabled || userId != null && userId > 0; }

    @AssertTrue(message = "Mini App URL must be HTTPS")
    public boolean isMiniAppUrlValid() { return miniAppUrl == null || miniAppUrl.isBlank() || miniAppUrl.startsWith("https://") && !miniAppUrl.matches(".*\\s.*"); }

    @AssertTrue(message = "watcher.telegram.admin.user-id is required when polling is enabled")
    public boolean isPollingIdentityValid() { return !pollingEnabled || (userId != null && userId > 0); }
}
