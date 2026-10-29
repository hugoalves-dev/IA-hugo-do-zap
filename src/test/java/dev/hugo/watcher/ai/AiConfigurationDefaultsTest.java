package dev.hugo.watcher.ai;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AiConfigurationDefaultsTest {
    @Test void enablingProviderAloneDoesNotEnablePaidClassification() {
        var settings = new AiClassificationProperties(true, null, null, "key", "model", 10, Duration.ofSeconds(1), null);
        assertFalse(settings.configured());
    }
}
