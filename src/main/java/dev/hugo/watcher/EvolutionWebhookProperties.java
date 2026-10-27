package dev.hugo.watcher;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("watcher.evolution")
public class EvolutionWebhookProperties {
    @NotBlank
    private String webhookToken;
    @NotBlank
    private String instance;
    @Min(1)
    @Max(4_194_304)
    private int maxBodyBytes = 1_048_576;

    public String getWebhookToken() { return webhookToken; }
    public void setWebhookToken(String webhookToken) { this.webhookToken = webhookToken; }
    public String getInstance() { return instance; }
    public void setInstance(String instance) { this.instance = instance; }
    public int getMaxBodyBytes() { return maxBodyBytes; }
    public void setMaxBodyBytes(int maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
}
