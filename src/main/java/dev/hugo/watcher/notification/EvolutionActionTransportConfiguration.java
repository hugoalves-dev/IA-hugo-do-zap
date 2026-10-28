package dev.hugo.watcher.notification;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EvolutionActionProperties.class)
public class EvolutionActionTransportConfiguration {
    @Bean
    @ConditionalOnExpression("${watcher.decision-producer.real-mode-enabled:false} and ${watcher.whatsapp-actions.real-send-enabled:false} and ${watcher.whatsapp-actions.evolution.enabled:false}")
    EvolutionActionTransport httpEvolutionActionTransport(EvolutionActionProperties properties) {
        return new HttpEvolutionActionTransport(properties);
    }

    @Bean
    @ConditionalOnMissingBean(EvolutionActionTransport.class)
    EvolutionActionTransport disabledEvolutionActionTransport() {
        return new DisabledEvolutionActionTransport();
    }
}
