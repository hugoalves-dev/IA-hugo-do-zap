package dev.hugo.watcher.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EvolutionActionProperties.class)
public class EvolutionActionTransportConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "watcher.whatsapp-actions", name = "real-send-enabled", havingValue = "false", matchIfMissing = true)
    EvolutionActionTransport disabledEvolutionActionTransport() {
        return new DisabledEvolutionActionTransport();
    }

    @Bean
    @ConditionalOnProperty(prefix = "watcher.whatsapp-actions", name = "real-send-enabled", havingValue = "true")
    EvolutionActionTransport httpEvolutionActionTransport(EvolutionActionProperties properties) {
        return new HttpEvolutionActionTransport(properties);
    }
}
