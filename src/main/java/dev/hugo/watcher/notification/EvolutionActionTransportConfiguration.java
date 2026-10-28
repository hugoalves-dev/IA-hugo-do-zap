package dev.hugo.watcher.notification;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EvolutionActionProperties.class)
public class EvolutionActionTransportConfiguration {
    @Bean
    EvolutionActionTransport disabledEvolutionActionTransport() {
        return new DisabledEvolutionActionTransport();
    }
}
