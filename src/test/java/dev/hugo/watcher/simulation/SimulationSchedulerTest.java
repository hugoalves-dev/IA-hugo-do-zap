package dev.hugo.watcher.simulation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

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

    @Test void realModeKeepsSchedulerAndIgnoresShadowAdminPause() {
        contexts.withPropertyValues("watcher.simulation.enabled=false", "watcher.decision-producer.real-mode-enabled=true")
                .run(context -> {
                    JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
                    DebounceService debounce = context.getBean(DebounceService.class);
                    when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);

                    assertTrue(context.getBeansOfType(SimulationScheduler.class).size() > 0);
                    context.getBean(SimulationScheduler.class).processReady();

                    org.mockito.Mockito.verify(debounce).claimReady(org.mockito.ArgumentMatchers.any());
                });
    }

    @Test void runtimeAdminSwitchStopsProcessingReadyBatches() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);
        DebounceService debounce = mock(DebounceService.class);
        new SimulationScheduler(debounce, mock(SimulationService.class), Clock.systemUTC(), jdbc).processReady();
        verifyNoInteractions(debounce);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SimulationScheduler.class)
    static class SchedulerTestConfiguration {
        @Bean DebounceService debounceService() { return mock(DebounceService.class); }
        @Bean SimulationService simulationService() { return mock(SimulationService.class); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean JdbcTemplate jdbcTemplate() { return mock(JdbcTemplate.class); }
    }
}
