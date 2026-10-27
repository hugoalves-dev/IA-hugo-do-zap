package dev.hugo.watcher;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.boot.SpringApplication;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WatcherSchedulingConfigurationTest {
    @Test void snapshotModeOverridesManualSchedulingAndSelectsNonWebApplication() {
        new ApplicationContextRunner()
                .withInitializer(context -> {
                    var app = new SpringApplication();
                    new LegacyImportEnvironmentPostProcessor().postProcessEnvironment(context.getEnvironment(), app);
                })
                .withUserConfiguration(WatcherSchedulingConfiguration.class)
                .withPropertyValues("watcher.legacy-import.snapshot=/tmp/copy.db", "watcher.runtime.scheduling-enabled=true")
                .run(context -> {
                    assertFalse(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).size() > 0);
                    assertEquals("none", context.getEnvironment().getProperty("spring.main.web-application-type"));
                });
    }
    @Test void emptySnapshotStillForcesSafeStartup() {
        new ApplicationContextRunner()
                .withInitializer(context -> new LegacyImportEnvironmentPostProcessor()
                        .postProcessEnvironment(context.getEnvironment(), new SpringApplication()))
                .withUserConfiguration(WatcherSchedulingConfiguration.class)
                .withPropertyValues("watcher.legacy-import.snapshot=", "watcher.runtime.scheduling-enabled=true")
                .run(context -> {
                    assertFalse(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).size() > 0);
                    assertEquals("none", context.getEnvironment().getProperty("spring.main.web-application-type"));
                });
    }
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
