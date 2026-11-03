package dev.hugo.watcher;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "watcher.rabbitmq")
public class RabbitMessagingProperties {
    private boolean enabled;
    private String exchange = "watcher.events";
    private Duration confirmTimeout = Duration.ofSeconds(5);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getExchange() { return exchange; }
    public void setExchange(String exchange) { this.exchange = exchange; }
    public Duration getConfirmTimeout() { return confirmTimeout; }
    public void setConfirmTimeout(Duration confirmTimeout) { this.confirmTimeout = confirmTimeout; }
}
