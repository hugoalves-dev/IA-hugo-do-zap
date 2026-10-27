package dev.hugo.watcher.simulation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SimulationSchedulerTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(SchedulerTestConfiguration.class);

    @Test void enabledFalseRemovesSimulationScheduler() {
        contexts.withPropertyValues("watcher.simulation.enabled=false").run(context ->
                assertFalse(context.getBeansOfType(SimulationScheduler.class).size() > 0));
    }

    @Test void simulationSchedulerRemainsEnabledByDefault() {
        contexts.run(context -> assertTrue(context.getBeansOfType(SimulationScheduler.class).size() > 0));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SimulationScheduler.class)
    static class SchedulerTestConfiguration {
        @Bean DebounceService debounceService() { return mock(DebounceService.class); }
        @Bean SimulationService simulationService() { return mock(SimulationService.class); }
        @Bean Clock clock() { return Clock.systemUTC(); }
    }
}
