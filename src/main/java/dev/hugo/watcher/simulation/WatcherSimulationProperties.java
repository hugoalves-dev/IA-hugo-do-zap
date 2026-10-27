package dev.hugo.watcher.simulation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("watcher.simulation")
public class WatcherSimulationProperties {
    private boolean enabled = true;
    @Min(1) @Max(1000) private int maxMessages = 50;
    @Min(1) @Max(5000) private int maxPendingBatches = 1000;
    @Min(1) @Max(60) private int inactivitySeconds = 10;
    @Min(1) @Max(300) private int maxWaitSeconds = 30;
    @Min(1) @Max(7) private int sensitiveRetentionDays = 7;
    @Min(1) @Max(365) private int auditRetentionDays = 90;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public int getMaxMessages() { return maxMessages; }
    public void setMaxMessages(int value) { maxMessages = value; }
    public int getMaxPendingBatches() { return maxPendingBatches; }
    public void setMaxPendingBatches(int value) { maxPendingBatches = value; }
    public int getInactivitySeconds() { return inactivitySeconds; }
    public void setInactivitySeconds(int value) { inactivitySeconds = value; }
    public int getMaxWaitSeconds() { return maxWaitSeconds; }
    public void setMaxWaitSeconds(int value) { maxWaitSeconds = value; }
    public int getSensitiveRetentionDays() { return sensitiveRetentionDays; }
    public void setSensitiveRetentionDays(int value) { sensitiveRetentionDays = value; }
    public int getAuditRetentionDays() { return auditRetentionDays; }
    public void setAuditRetentionDays(int value) { auditRetentionDays = value; }
}
