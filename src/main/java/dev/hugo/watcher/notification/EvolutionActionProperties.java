package dev.hugo.watcher.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("watcher.whatsapp-actions.evolution")
public class EvolutionActionProperties {
    private URI baseUrl;
    private String instance;
    private String apiKey;
    private Duration timeout = Duration.ofSeconds(15);
    public URI getBaseUrl() { return baseUrl; }
    public void setBaseUrl(URI value) { baseUrl = value; }
    public String getInstance() { return instance; }
    public void setInstance(String value) { instance = value; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration value) { timeout = value; }
}
