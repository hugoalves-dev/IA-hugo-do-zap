package dev.hugo.watcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/** Forces one-shot import startup into a non-web, non-scheduled mode whenever a snapshot is supplied. */
public final class LegacyImportEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.containsProperty("watcher.legacy-import.snapshot")) {
            environment.getPropertySources().addFirst(new MapPropertySource("legacyImportSafety",
                    Map.of("watcher.runtime.scheduling-enabled", "false", "spring.main.web-application-type", "none")));
        }
    }
}
