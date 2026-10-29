package dev.hugo.watcher;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Declarative operational controls. The persisted emergency latch can only restrict these. */
@Validated
@ConfigurationProperties("watcher.automation")
public class AutomationProperties {
    private boolean killSwitchEnabled = true;
    private boolean intelligentAlertsEnabled;
    private boolean autoReplyEnabled;
    public boolean isKillSwitchEnabled() { return killSwitchEnabled; }
    public void setKillSwitchEnabled(boolean value) { killSwitchEnabled = value; }
    public boolean isIntelligentAlertsEnabled() { return intelligentAlertsEnabled; }
    public void setIntelligentAlertsEnabled(boolean value) { intelligentAlertsEnabled = value; }
    public boolean isAutoReplyEnabled() { return autoReplyEnabled; }
    public void setAutoReplyEnabled(boolean value) { autoReplyEnabled = value; }
}
