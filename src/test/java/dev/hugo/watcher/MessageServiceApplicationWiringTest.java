package dev.hugo.watcher;

import dev.hugo.watcher.notification.DecisionActionProducer;
import dev.hugo.watcher.notification.DisabledEvolutionActionTransport;
import dev.hugo.watcher.notification.EvolutionActionTransport;
import dev.hugo.watcher.simulation.DebounceService;
import dev.hugo.watcher.simulation.SimulationScheduler;
import dev.hugo.watcher.simulation.WatcherSimulationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class MessageServiceApplicationWiringTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test void completeApplicationWiresMessageServiceWithDefaults() {
        assertCompleteApplicationStarts(false);
    }

    @Test void completeApplicationWiresMessageServiceInRealOnlyModeWithoutCredentials() {
        assertCompleteApplicationStarts(true);
    }

    private void assertCompleteApplicationStarts(boolean realOnly) {
        var builder = new SpringApplicationBuilder(WatcherApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(),
                        "watcher.telegram-bot-token=synthetic-test-token",
                        "watcher.telegram-chat-id=123456789",
                        "watcher.group-jids=120363000000000000@g.us",
                        "watcher.evolution.webhook-token=synthetic-webhook-token",
                        "watcher.evolution.instance=synthetic-instance",
                        "watcher.runtime.scheduling-enabled=false",
                        "logging.level.root=ERROR");
        if (realOnly) {
            builder.properties("watcher.simulation.enabled=false",
                    "watcher.decision-producer.real-mode-enabled=true",
                    "watcher.whatsapp-actions.real-send-enabled=false",
                    "watcher.whatsapp-actions.evolution.enabled=false");
        }

        try (ConfigurableApplicationContext context = builder.run()) {
            assertNotNull(context.getBean(MessageService.class));
            assertNotNull(context.getBean(DebounceService.class));
            assertNotNull(context.getBean(SimulationScheduler.class));
            assertEquals(!realOnly, context.getBean(WatcherSimulationProperties.class).isEnabled());
            assertEquals(realOnly, context.getBean(DecisionActionProducer.class).realModeEnabled());
            assertInstanceOf(DisabledEvolutionActionTransport.class, context.getBean(EvolutionActionTransport.class));
        }
    }
}
