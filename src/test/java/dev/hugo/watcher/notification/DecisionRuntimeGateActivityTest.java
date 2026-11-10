package dev.hugo.watcher.notification;

import dev.hugo.watcher.OperationalState;
import dev.hugo.watcher.WatcherProperties;
import dev.hugo.watcher.policy.HolidayService;
import dev.hugo.watcher.policy.PersistedPolicyRepository;
import dev.hugo.watcher.simulation.OwnerConversationActivity;
import dev.hugo.watcher.simulation.OwnerConversationActivityRepository;
import dev.hugo.watcher.simulation.WatcherSimulationProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DecisionRuntimeGateActivityTest {
    private static final Instant NOW = Instant.parse("2026-11-10T20:26:52Z");

    @Test void ownerBecomingActiveAfterDecisionBlocksReplyAtDispatchGate() {
        UUID decisionId = UUID.randomUUID();
        var queries = mock(DecisionRuntimeQueryRepository.class);
        when(queries.contextForDecision(decisionId, NOW)).thenReturn(List.of(
                new DecisionRuntimeQueryRepository.Context("canonical@s.whatsapp.net", "{}", "NORMAL",
                        "CLASSIFIED", 0.8, null, "Contact")));
        var activity = mock(OwnerConversationActivityRepository.class);
        when(activity.activity(eq("canonical@s.whatsapp.net"), eq(NOW), eq(Duration.ofMinutes(60))))
                .thenReturn(OwnerConversationActivity.ACTIVE);
        var gate = new DecisionRuntimeGate(queries, Clock.fixed(NOW, ZoneOffset.UTC), new WatcherProperties(), "",
                mock(PersistedPolicyRepository.class), mock(HolidayService.class), activity,
                new WatcherSimulationProperties());
        OperationalState operational = mock(OperationalState.class);
        when(operational.classification()).thenReturn(true);
        when(operational.replies()).thenReturn(true);
        gate.configureOperationalState(operational);

        assertFalse(gate.allows(decisionId, false));
        verify(activity).activity("canonical@s.whatsapp.net", NOW, Duration.ofMinutes(60));
    }

    @Test void unavailableOwnerActivityFailsClosedAtDispatchGate() {
        UUID decisionId = UUID.randomUUID();
        var queries = mock(DecisionRuntimeQueryRepository.class);
        when(queries.contextForDecision(decisionId, NOW)).thenReturn(List.of(
                new DecisionRuntimeQueryRepository.Context("canonical@s.whatsapp.net", "{}", "NORMAL",
                        "CLASSIFIED", 0.8, null, "Contact")));
        var activity = mock(OwnerConversationActivityRepository.class);
        when(activity.activity(anyString(), any(), any())).thenReturn(OwnerConversationActivity.UNKNOWN);
        var gate = new DecisionRuntimeGate(queries, Clock.fixed(NOW, ZoneOffset.UTC), new WatcherProperties(), "",
                mock(PersistedPolicyRepository.class), mock(HolidayService.class), activity,
                new WatcherSimulationProperties());
        OperationalState operational = mock(OperationalState.class);
        when(operational.classification()).thenReturn(true);
        when(operational.replies()).thenReturn(true);
        gate.configureOperationalState(operational);

        assertFalse(gate.allows(decisionId, false));
    }
}
