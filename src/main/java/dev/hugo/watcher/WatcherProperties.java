package dev.hugo.watcher;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Validated
@ConfigurationProperties("watcher")
public class WatcherProperties {
    @NotBlank
    private String n8nUrl;
    @NotBlank
    private String n8nSecret;
    @NotBlank
    private String timezone = "America/Sao_Paulo";
    @NotEmpty
    private Set<@NotNull @Min(1) @Max(7) Integer> notificationDays = Set.of(1, 2, 3, 4, 5);
    @NotEmpty
    @Valid
    private List<@NotNull TimeWindow> notificationWindows = List.of(new TimeWindow(LocalTime.of(8, 0), LocalTime.of(18, 0)));
    @NotEmpty
    @Size(max = 500)
    private List<@NotBlank String> groupJids = List.of();
    @NotBlank
    private String allowedNameRegex = "(^|[^A-Z0-9])JC([^A-Z0-9]|$)";
    private boolean includeGroups = true;
    private List<@NotBlank String> neverNotifyGroupNames = List.of("233/234");
    @Min(1)
    @Max(1000)
    private int maxBatchSize = 100;
    @Min(1)
    @Max(7)
    private int retentionDays = 7;

    public String getN8nUrl() { return n8nUrl; }
    public void setN8nUrl(String n8nUrl) { this.n8nUrl = n8nUrl; }
    public String getN8nSecret() { return n8nSecret; }
    public void setN8nSecret(String n8nSecret) { this.n8nSecret = n8nSecret; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public Set<Integer> getNotificationDays() { return notificationDays; }
    public void setNotificationDays(Set<Integer> notificationDays) { this.notificationDays = notificationDays; }
    public List<TimeWindow> getNotificationWindows() { return notificationWindows; }
    public void setNotificationWindows(List<TimeWindow> notificationWindows) { this.notificationWindows = notificationWindows; }
    public List<String> getGroupJids() { return groupJids; }
    public void setGroupJids(List<String> groupJids) { this.groupJids = groupJids; }
    public String getAllowedNameRegex() { return allowedNameRegex; }
    public void setAllowedNameRegex(String allowedNameRegex) { this.allowedNameRegex = allowedNameRegex; }
    public boolean isIncludeGroups() { return includeGroups; }
    public void setIncludeGroups(boolean includeGroups) { this.includeGroups = includeGroups; }
    public List<String> getNeverNotifyGroupNames() { return neverNotifyGroupNames; }
    public void setNeverNotifyGroupNames(List<String> neverNotifyGroupNames) { this.neverNotifyGroupNames = neverNotifyGroupNames; }
    public int getMaxBatchSize() { return maxBatchSize; }
    public void setMaxBatchSize(int maxBatchSize) { this.maxBatchSize = maxBatchSize; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }

    @jakarta.validation.constraints.AssertTrue(message = "must be an absolute HTTP(S) URL using HTTPS except for the internal n8n host")
    public boolean isN8nUrlSecure() {
        if (n8nUrl == null || n8nUrl.isBlank()) return true;
        try {
            URI uri = URI.create(n8nUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            return uri.isAbsolute() && host != null && uri.getRawUserInfo() == null
                    && ("https".equalsIgnoreCase(scheme) || ("http".equalsIgnoreCase(scheme) && "n8n".equalsIgnoreCase(host)));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @jakarta.validation.constraints.AssertTrue(message = "must be a valid timezone")
    public boolean isTimezoneValid() {
        if (timezone == null || timezone.isBlank()) return true;
        try { ZoneId.of(timezone); return true; }
        catch (RuntimeException ex) { return false; }
    }

    @jakarta.validation.constraints.AssertTrue(message = "must be a valid regular expression")
    public boolean isAllowedNameRegexValid() {
        if (allowedNameRegex == null || allowedNameRegex.isBlank()) return true;
        try { Pattern.compile(allowedNameRegex); return true; }
        catch (RuntimeException ex) { return false; }
    }

    public record TimeWindow(@NotNull LocalTime start, @NotNull LocalTime end) {
        @jakarta.validation.constraints.AssertTrue(message = "end must be after start")
        public boolean isIntervalValid() { return start == null || end == null || start.isBefore(end); }
    }
}
