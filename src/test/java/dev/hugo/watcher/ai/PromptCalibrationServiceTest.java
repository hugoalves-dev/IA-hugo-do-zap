package dev.hugo.watcher.ai;

import dev.hugo.watcher.OperationalState;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PromptCalibrationServiceTest {
    @Test void disabledShadowStopsBeforeAnyProviderCall() {
        var requests = new AtomicInteger();
        ProviderTransport transport = (request, timeout, max) -> {
            requests.incrementAndGet();
            return new ProviderTransport.Response(200, "{}");
        };
        var props = properties();
        OperationalState operational = mock(OperationalState.class);
        when(operational.simulation()).thenReturn(false);
        var service = new PromptCalibrationService(new ClassificationPromptService(new ClassificationPromptServiceTest.MemoryRepository()),
                props, mock(BudgetService.class), operational, Clock.systemUTC(), transport);

        String result = service.run(0);

        assertTrue(result.contains("shadow_disabled_by_environment_or_pause"));
        assertEquals(0, requests.get());
    }

    @Test void disabledBudgetStopsBeforeProviderCallEvenWhenShadowIsEnabled() {
        var requests = new AtomicInteger();
        ProviderTransport transport = (request, timeout, max) -> {
            requests.incrementAndGet();
            return new ProviderTransport.Response(200, "{}");
        };
        var props = new AiClassificationProperties(true, null, null, "fake-key", "test-model", 0,
                Duration.ofSeconds(2), null, true, true, true, false, 300, false);
        OperationalState operational = mock(OperationalState.class);
        when(operational.simulation()).thenReturn(true);
        when(operational.classification()).thenReturn(true);
        var budgets = mock(BudgetService.class);
        var service = new PromptCalibrationService(new ClassificationPromptService(new ClassificationPromptServiceTest.MemoryRepository()),
                props, budgets, operational, Clock.systemUTC(), transport);

        String result = service.run(0);

        assertTrue(result.contains("local_budget_disabled"));
        assertEquals(0, requests.get());
        verifyNoInteractions(budgets);
    }

    @Test void syntheticFixtureUsesRequestedRevisionAndProducesOnlyClassificationPreview() {
        String response = "{\"choices\":[{\"message\":{\"content\":\"{\\\"intent\\\":\\\"NONE\\\",\\\"requestDetected\\\":false,\\\"notificationRecommended\\\":true,\\\"replyRecommended\\\":false,\\\"urgency\\\":\\\"NORMAL\\\",\\\"evidence\\\":[\\\"CONTEXTUAL_DEPENDENCY\\\"],\\\"confidence\\\":0.85}\"}}],\"usage\":{\"cost\":0}}";
        var fake = new FakeProviderTransport(request -> FakeProviderTransport.response(200, response));
        OperationalState operational = mock(OperationalState.class);
        when(operational.simulation()).thenReturn(true);
        when(operational.classification()).thenReturn(true);
        var props = properties();
        var budgets = mock(BudgetService.class);
        when(budgets.reserve(any(), any())).thenReturn(new Reservation(java.util.UUID.randomUUID(), 10,
                java.time.LocalDate.now(), java.time.LocalDate.now().withDayOfMonth(1)));
        var repo = new ClassificationPromptServiceTest.MemoryRepository();
        repo.createDraft("Prioritize a reply to the owner's recent question.", "a".repeat(64), 1001,
                java.time.Instant.now());
        var service = new PromptCalibrationService(new ClassificationPromptService(repo), props, budgets,
                operational, Clock.systemUTC(), fake);

        String result = service.run(1);

        assertTrue(result.contains("Triagem simulada: NOTIFY_ONLY"));
        assertTrue(result.contains("não foram avaliados"));
        assertTrue(FakeProviderTransport.requestBody(fake.requests().getFirst()).contains("Prioritize a reply"));
        verify(budgets).reserve(any(), any());
        assertFalse(result.contains("notificação foi enviada"));
    }

    private static AiClassificationProperties properties() {
        return new AiClassificationProperties(true, null, null, "fake-key", "test-model", 10,
                Duration.ofSeconds(2), null, true, false, true, false, 300, false);
    }
}
