package dev.hugo.watcher;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("watcher.archive-mode")
public class ArchiveModeProperties {
    private String apiToken;

    public String getApiToken() { return apiToken; }
    public void setApiToken(String apiToken) { this.apiToken = apiToken; }
}
