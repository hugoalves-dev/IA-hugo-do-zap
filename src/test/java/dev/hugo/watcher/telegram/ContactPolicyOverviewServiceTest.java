package dev.hugo.watcher.telegram;

import dev.hugo.watcher.IdentityService;
import dev.hugo.watcher.OperationalState;
import dev.hugo.watcher.WatcherProperties;
import dev.hugo.watcher.policy.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContactPolicyOverviewServiceTest {
    @Test void presentsEffectiveSourcesConsentAndGlobalGatesWithoutChangingThem() {
        ContactPolicyOverviewRepository repository = mock(ContactPolicyOverviewRepository.class);
        PersistedPolicyRepository policies = mock(PersistedPolicyRepository.class);
        AvailabilityService availability = mock(AvailabilityService.class);
        IdentityService identities = mock(IdentityService.class);
        OperationalState operational = mock(OperationalState.class);
        WatcherProperties watcher = new WatcherProperties();
        String jid = "15551234567@s.whatsapp.net";
        when(identities.canonicalJidFor(jid)).thenReturn(jid);
        when(identities.resolveFriendlyDisplayName(jid)).thenReturn("Contato de teste");
        when(repository.known(jid)).thenReturn(true);
        when(repository.fallbackProfile(jid, watcher.getAllowedNameRegex())).thenReturn("ANYTIME");
        when(repository.authorizations(jid)).thenReturn(List.of("CONTACT_REQUEST"));
        when(repository.consents(eq(jid), any())).thenReturn(List.of("openrouter:CONTACT_REQUEST"));
        when(operational.simulation()).thenReturn(false);
        when(operational.emergency()).thenReturn(true);
        when(operational.killSwitch()).thenReturn(true);
        when(operational.classificationConfigured()).thenReturn(false);
        when(operational.classification()).thenReturn(false);
        EnumMap<PolicyField, ResolvedPolicyField> fields = new EnumMap<>(PolicyField.class);
        fields.put(PolicyField.MODE, new ResolvedPolicyField(ScheduleMode.ANYTIME, PolicySource.PROFILE));
        EffectivePolicy effective = new EffectivePolicy(fields, null);
        when(policies.forChat(jid, "ANYTIME")).thenReturn(effective);
        when(availability.at(any(), any(), same(effective))).thenReturn(
                new Availability(AvailabilityState.AVAILABLE, AvailabilitySource.ANYTIME, "anytime profile"));
        var service = new ContactPolicyOverviewService(repository, policies, availability, watcher, identities,
                operational, Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneId.of("UTC")));

        String response = service.describe(jid);

        assertTrue(response.contains("Contato: Contato de teste"));
        assertTrue(response.contains("MODE: ANYTIME · origem PROFILE"));
        assertTrue(response.contains("openrouter:CONTACT_REQUEST"));
        assertTrue(response.contains("emergência ativa"));
        assertTrue(response.contains("Classificação configurada: não"));
        assertTrue(response.contains("Campos de política incompletos:"));
    }

    @Test void unknownContactDoesNotAttemptPolicyResolution() {
        ContactPolicyOverviewRepository repository = mock(ContactPolicyOverviewRepository.class);
        PersistedPolicyRepository policies = mock(PersistedPolicyRepository.class);
        IdentityService identities = mock(IdentityService.class);
        when(identities.canonicalJidFor("unknown@s.whatsapp.net")).thenReturn("unknown@s.whatsapp.net");
        when(repository.known("unknown@s.whatsapp.net")).thenReturn(false);
        var service = new ContactPolicyOverviewService(repository, policies, mock(AvailabilityService.class),
                new WatcherProperties(), identities, mock(OperationalState.class), Clock.systemUTC());

        assertEquals("Conversa não encontrada.", service.describe("unknown@s.whatsapp.net"));
        verifyNoInteractions(policies);
    }
}
