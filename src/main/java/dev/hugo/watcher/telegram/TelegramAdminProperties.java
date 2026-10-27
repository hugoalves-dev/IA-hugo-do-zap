package dev.hugo.watcher.telegram;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("watcher.telegram.admin")
public class TelegramAdminProperties {
    private boolean pollingEnabled;
    @Positive
    private Long userId;

    public boolean isPollingEnabled() { return pollingEnabled; }
    public void setPollingEnabled(boolean pollingEnabled) { this.pollingEnabled = pollingEnabled; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    @AssertTrue(message = "watcher.telegram.admin.user-id is required when polling is enabled")
    public boolean isPollingIdentityValid() { return !pollingEnabled || (userId != null && userId > 0); }
}
