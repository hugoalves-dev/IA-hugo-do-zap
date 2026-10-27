package dev.hugo.watcher;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatcherSchedulingConfigurationTest {
    @Test void oneShotImportCanDisableScheduledWorkers() {
        new ApplicationContextRunner()
                .withUserConfiguration(WatcherSchedulingConfiguration.class)
                .withPropertyValues("watcher.runtime.scheduling-enabled=false")
                .run(context -> assertFalse(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).size() > 0));
    }

    @Test void regularRuntimeKeepsSchedulingEnabledByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(WatcherSchedulingConfiguration.class)
                .run(context -> assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).size() > 0));
    }
}
