package dev.hugo.watcher;

import dev.hugo.watcher.ai.AiClassificationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.junit.jupiter.api.Assertions.*;

class OperationalBindingTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer()).withUserConfiguration(Config.class);
    @Configuration(proxyBeanMethods=false)
    @EnableConfigurationProperties({AiClassificationProperties.class, AutomationProperties.class})
    static class Config { }
    @Test void absentVariablesFailClosed() {
        runner.run(ctx -> {
            var ai=ctx.getBean(AiClassificationProperties.class); var flags=ctx.getBean(AutomationProperties.class);
            assertFalse(ai.enabled()); assertFalse(ai.classificationEnabled()); assertFalse(ai.disableLocalBudget());
            assertTrue(ai.requireChatConsent()); assertTrue(flags.isKillSwitchEnabled());
            assertFalse(flags.isIntelligentAlertsEnabled()); assertFalse(flags.isAutoReplyEnabled());
        });
    }
    @Test void underscoreEnvironmentNamesBindAsDocumented() {
        runner.withPropertyValues("WATCHER_AI_CLASSIFICATION_ENABLED=true", "WATCHER_AI_DISABLE_LOCAL_BUDGET=true",
                "WATCHER_AI_REQUIRE_CHAT_CONSENT=false", "WATCHER_AUTOMATION_KILL_SWITCH_ENABLED=false",
                "WATCHER_AUTOMATION_INTELLIGENT_ALERTS_ENABLED=true", "WATCHER_AUTOMATION_AUTO_REPLY_ENABLED=true")
            .run(ctx -> {
                var ai=ctx.getBean(AiClassificationProperties.class); var flags=ctx.getBean(AutomationProperties.class);
                assertTrue(ai.classificationEnabled()); assertTrue(ai.disableLocalBudget()); assertFalse(ai.requireChatConsent());
                assertFalse(flags.isKillSwitchEnabled()); assertTrue(flags.isIntelligentAlertsEnabled()); assertTrue(flags.isAutoReplyEnabled());
            });
    }
}
