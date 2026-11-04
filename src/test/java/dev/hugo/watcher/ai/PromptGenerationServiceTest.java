package dev.hugo.watcher.ai;

import dev.hugo.watcher.OperationalState;
import dev.hugo.watcher.ai.ClassificationPromptRepository.Draft;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PromptGenerationServiceTest {
    @Test void storesValidatedProposalAsEditableAiDraft() {
        var fixture = fixture("Consider urgency independently from relevance.");
        var saved = new Draft(fixture.draft.id(), 44, null, "Consider urgency independently from relevance.",
                "ai_assisted", "Make urgency independent.", "editing", 0, 1, fixture.draft.expiresAt());
        when(fixture.prompts.saveProposal(any(), anyLong(), anyLong(), anyString(), eq("ai_assisted"), anyString(), any()))
                .thenReturn(saved);
        when(fixture.repository.draft(any(), anyLong(), any())).thenReturn(Optional.of(fixture.draft), Optional.of(saved));

        var proposal = fixture.service.propose(fixture.draft.id(), 44, "Clarify urgency.", Instant.now());

        assertEquals("ai_assisted", proposal.draft().origin());
        assertEquals("Make urgency independent.", proposal.changeSummary());
        verify(fixture.prompts).saveProposal(eq(fixture.draft.id()), eq(44L), eq(0L),
                eq("Consider urgency independently from relevance."), eq("ai_assisted"), eq("Make urgency independent."), any());
    }

    @Test void refinementCapAndShadowGateBlockBeforeProviderCall() {
        var limited = fixture("Safe prompt.");
        when(limited.repository.draft(any(), anyLong(), any())).thenReturn(Optional.of(new Draft(
                limited.draft.id(), 44, null, "Safe prompt.", "ai_assisted", "", "editing", 3, 3, Instant.now().plusSeconds(300))));
        assertThrows(IllegalStateException.class, () -> limited.service.refine(limited.draft.id(), 44, "Clarify.", Instant.now()));
        verifyNoInteractions(limited.budgets);

        var disabled = fixture("Safe prompt.");
        when(disabled.operational.simulation()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> disabled.service.propose(disabled.draft.id(), 44, "Clarify.", Instant.now()));
        verifyNoInteractions(disabled.budgets);
        assertEquals(0, disabled.calls.get());
    }

    private static Fixture fixture(String generated) {
        UUID id = UUID.randomUUID();
        var draft = new Draft(id, 44, null, "Base operational prompt.", "manual", "", "editing", 0, 0, Instant.now().plusSeconds(300));
        var prompts = mock(ClassificationPromptService.class);
        var repository = mock(ClassificationPromptRepository.class);
        when(repository.draft(eq(id), eq(44L), any())).thenReturn(Optional.of(draft));
        var validator = new ClassificationPromptValidator();
        var operational = mock(OperationalState.class);
        when(operational.simulation()).thenReturn(true);
        when(operational.classification()).thenReturn(true);
        var properties = new AiClassificationProperties(true, null, null, "fake-key", "test-model", 100,
                Duration.ofSeconds(2), null, true, false, true, false, 300, false);
        String content = "{\"prompt\":\"" + generated + "\",\"summary\":\"Make urgency independent.\"}";
        String response = "{\"choices\":[{\"message\":{\"content\":" + tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(content) + "}}]}";
        AtomicInteger calls = new AtomicInteger();
        ProviderTransport transport = (request, timeout, max) -> { calls.incrementAndGet(); return new ProviderTransport.Response(200, response); };
        var budgets = mock(BudgetService.class);
        when(budgets.reserve(any(), any())).thenReturn(new Reservation(UUID.randomUUID(), 100,
                LocalDate.now(), LocalDate.now().withDayOfMonth(1)));
        var generator = new OpenRouterPromptGenerator(properties, transport, budgets, Clock.systemUTC());
        var service = new PromptGenerationService(prompts, repository, validator, generator, properties, operational);
        return new Fixture(draft, prompts, repository, budgets, operational, transport, calls, service);
    }

    private record Fixture(Draft draft, ClassificationPromptService prompts, ClassificationPromptRepository repository,
                           BudgetService budgets, OperationalState operational, ProviderTransport transport, AtomicInteger calls,
                           PromptGenerationService service) { }
}
