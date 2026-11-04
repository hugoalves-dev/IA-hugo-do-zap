package dev.hugo.watcher.ai;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenRouterPromptGeneratorTest {
    @Test void sendsOnlyExplicitInstructionsAndOperationalPromptWithStrictBoundedSchema() {
        String response = "{\"choices\":[{\"message\":{\"content\":\"{\\\"prompt\\\":\\\"Consider deadlines.\\\",\\\"summary\\\":\\\"Clarified urgency.\\\"}\"}}]}";
        var fake = new FakeProviderTransport(request -> FakeProviderTransport.response(200, response));
        var budget = budget();
        var generator = new OpenRouterPromptGenerator(properties(), fake, budget, Clock.systemUTC());

        var proposal = generator.generate("Base operational prompt.", "Goal: clarify urgency.", () -> true);

        assertEquals("Consider deadlines.", proposal.prompt());
        String body = FakeProviderTransport.requestBody(fake.requests().getFirst());
        assertTrue(body.contains("Base operational prompt."));
        assertTrue(body.contains("Goal: clarify urgency."));
        assertTrue(body.contains("2048"));
        assertTrue(body.contains("additionalProperties"));
        assertTrue(body.contains("\"zdr\":true"));
        assertTrue(body.contains("\"data_collection\":\"deny\""));
        assertTrue(body.contains("\"allow_fallbacks\":false"));
        assertTrue(body.contains("\"require_parameters\":true"));
        assertEquals("openrouter.ai", fake.requests().getFirst().uri().getHost());
        assertFalse(body.contains("fake-key"));
        verify(budget).reserve(any(), any());
    }

    @Test void disabledGateAndMalformedOutputFailWithoutExposingProviderBody() {
        var gate = new OpenRouterPromptGenerator(properties(), (request, timeout, max) -> { fail("must not call"); return null; }, budget(), Clock.systemUTC());
        assertThrows(IllegalStateException.class, () -> gate.generate("Base.", "Improve clarity.", () -> false));

        var malformed = new OpenRouterPromptGenerator(properties(), (request, timeout, max) -> new ProviderTransport.Response(200, "secret provider payload"), budget(), Clock.systemUTC());
        var error = assertThrows(IllegalStateException.class, () -> malformed.generate("Base.", "Improve clarity.", () -> true));
        assertFalse(error.getMessage().contains("secret"));
    }

    private static BudgetService budget() {
        BudgetService budget = mock(BudgetService.class);
        when(budget.reserve(any(), any())).thenReturn(new Reservation(UUID.randomUUID(), 100, LocalDate.now(), LocalDate.now().withDayOfMonth(1)));
        return budget;
    }
    private static AiClassificationProperties properties() {
        return new AiClassificationProperties(true, null, null, "fake-key", "test-model", 100,
                Duration.ofSeconds(2), null, true, false, true, false, 300, false);
    }
}
