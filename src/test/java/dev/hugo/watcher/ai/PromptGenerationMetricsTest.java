package dev.hugo.watcher.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PromptGenerationMetricsTest {
    @Test void jobMetricsUseOnlyBoundedTags() {
        MeterRegistry registry = new SimpleMeterRegistry();
        Class<?> type = assertDoesNotThrow(() -> Class.forName("dev.hugo.watcher.ai.PromptGenerationMetrics"));
        Constructor<?> constructor = assertDoesNotThrow(() -> type.getConstructor(MeterRegistry.class));
        Object metrics = assertDoesNotThrow(() -> constructor.newInstance(registry));

        invoke(metrics, "enqueued", new Class<?>[]{String.class}, "GENERATE");
        invoke(metrics, "terminal", new Class<?>[]{String.class, String.class, String.class, String.class, Duration.class},
                "GENERATE", "FAILED", "PREFLIGHT", "BUDGET_MODE_BLOCKED", Duration.ofMillis(21));
        invoke(metrics, "provider", new Class<?>[]{String.class, String.class, Duration.class},
                "REFINE", "HTTP_RESPONSE_RECEIVED", Duration.ofMillis(120));

        assertEquals(1.0, registry.get("watcher.prompt.generation.jobs")
                .tag("operation", "GENERATE").tag("status", "PENDING").tag("reason_code", "NONE")
                .counter().count());
        assertEquals(1.0, registry.get("watcher.prompt.generation.jobs")
                .tag("operation", "GENERATE").tag("status", "FAILED").tag("reason_code", "BUDGET_MODE_BLOCKED")
                .counter().count());
        assertEquals(1.0, registry.get("watcher.prompt.generation.stage")
                .tag("stage", "PREFLIGHT").tag("reason_code", "BUDGET_MODE_BLOCKED").counter().count());

        Set<String> allowed = Set.of("operation", "status", "stage", "reason_code", "outcome", "delivery_status");
        Set<String> observed = registry.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                .map(io.micrometer.core.instrument.Tag::getKey).collect(Collectors.toSet());
        assertTrue(allowed.containsAll(observed), observed.toString());
        assertFalse(observed.contains("jobId"));
        assertFalse(observed.contains("chatId"));
        assertFalse(observed.contains("reservationId"));
    }

    private static void invoke(Object target, String methodName, Class<?>[] parameters, Object... arguments) {
        Method method = assertDoesNotThrow(() -> target.getClass().getMethod(methodName, parameters));
        assertDoesNotThrow(() -> method.invoke(target, arguments));
    }
}
