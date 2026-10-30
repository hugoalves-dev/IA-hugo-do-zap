package dev.hugo.watcher.ai;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class AiConfigurationDefaultsTest {
    @Test void enablingProviderAloneDoesNotEnablePaidClassification() {
        var settings = new AiClassificationProperties(true, null, null, "key", "model", 10, Duration.ofSeconds(1), null);
        assertFalse(settings.configured());
    }
    @Test void unbudgetedGeminiNeedsSeparateOptIn() {
        var blocked = new AiClassificationProperties(true, "gemini-key", "gemini-model", "router-key", "router-model", 0, Duration.ofSeconds(1), null, true, true, false, false);
        var enabled = new AiClassificationProperties(true, "gemini-key", "gemini-model", "router-key", "router-model", 0, Duration.ofSeconds(1), null, true, true, false, true);
        assertFalse(blocked.fallbackConfigured());
        assertTrue(enabled.fallbackConfigured());
    }
    @Test void invalidTokenLimitFailsClosedAndDefaultsDisableReasoning() {
        var valid = new AiClassificationProperties(true, null, null, "key", "model", 10,
                Duration.ofSeconds(1), null, true, false, true, false, 300, false);
        var invalid = new AiClassificationProperties(true, null, null, "key", "model", 10,
                Duration.ofSeconds(1), null, true, false, true, false, 0, false);
        assertTrue(valid.configured());
        assertFalse(valid.openRouterReasoningEnabled());
        assertEquals(300, valid.openRouterMaxTokens());
        assertFalse(invalid.configured());
    }


}
