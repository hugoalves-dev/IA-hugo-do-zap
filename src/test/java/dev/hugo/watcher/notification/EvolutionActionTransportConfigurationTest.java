package dev.hugo.watcher.notification;

import dev.hugo.watcher.simulation.WatcherSimulationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

class EvolutionActionTransportConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(TransportTestConfiguration.class);

    @Test void shadowModeDefaultsOnAndCanBeDisabledIndependently() {
        context.run(c -> assertTrue(c.getBean(WatcherSimulationProperties.class).isEnabled()));
        context.withPropertyValues("watcher.simulation.enabled=false")
                .run(c -> assertFalse(c.getBean(WatcherSimulationProperties.class).isEnabled()));
    }

    @Test void realTransportDefaultsOff() {
        context.run(c -> {
            assertTrue(c.getBean(EvolutionActionTransport.class) instanceof DisabledEvolutionActionTransport);
            assertFalse(c.getEnvironment().getProperty("watcher.decision-producer.real-mode-enabled", Boolean.class, false));
            assertFalse(c.getEnvironment().getProperty("watcher.whatsapp-actions.real-send-enabled", Boolean.class, false));
        });
    }

    @Test void explicitRealFlagsAndValidCredentialsSelectHttpTransport() {
        context.withPropertyValues(
                "watcher.decision-producer.real-mode-enabled=true",
                "watcher.whatsapp-actions.real-send-enabled=true",
                "watcher.whatsapp-actions.evolution.enabled=true",
                "watcher.whatsapp-actions.evolution.base-url=https://evolution.example.test",
                "watcher.whatsapp-actions.evolution.instance=watcher-test",
                "watcher.whatsapp-actions.evolution.api-key=synthetic-test-key")
                .run(c -> {
                    assertTrue(c.isRunning());
                    assertInstanceOf(HttpEvolutionActionTransport.class, c.getBean(EvolutionActionTransport.class));
                });
    }

    @Test void explicitRealFlagsWithoutCredentialsFailStartup() {
        context.withPropertyValues(
                "watcher.decision-producer.real-mode-enabled=true",
                "watcher.whatsapp-actions.real-send-enabled=true",
                "watcher.whatsapp-actions.evolution.enabled=true")
                .run(c -> assertNotNull(c.getStartupFailure()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WatcherSimulationProperties.class)
    static class TransportTestConfiguration extends EvolutionActionTransportConfiguration { }
}
