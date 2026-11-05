package dev.hugo.watcher.notification;

import dev.hugo.watcher.WatcherProperties;
import dev.hugo.watcher.policy.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContactNotificationRulesTest {
    private static final String CHAT = "5511999999999@s.whatsapp.net";
    private final ContactNotificationRuleRepository repository = mock(ContactNotificationRuleRepository.class);
    private final PersistedPolicyRepository policies = mock(PersistedPolicyRepository.class);
    private final HolidayService holidays = mock(HolidayService.class);
    private final ContactNotificationRules rules = new ContactNotificationRules(repository,
            policies, new AvailabilityService(holidays), new WatcherProperties());

    @Test void normalizedTokensDoNotMatchSubstringsOrFailOnRepeatedTokens() {
        assertEquals("JC", ContactNotificationRules.marker("(jç)"));
        assertTrue(ContactNotificationRules.tokens("Rafael CH Ch").contains("CH"));
        assertFalse(ContactNotificationRules.tokens("Chamada JCX").contains("CH"));
        assertFalse(ContactNotificationRules.tokens("Chamada JCX").contains("JC"));
        assertThrows(IllegalArgumentException.class, () -> ContactNotificationRules.marker("J C"));
    }

    @Test void withoutRuleLegacyBehaviorRemainsUnchanged() {
        var defaultRoute = rules.route(CHAT, "Pessoa", false, false, false, Instant.now());
        assertEquals("ANYTIME", defaultRoute.profile());
        assertTrue(defaultRoute.allowed());
        assertFalse(defaultRoute.priority());
        verifyNoInteractions(policies, holidays);
    }

    @Test void jcUsesConfiguredProfileScheduleAndAttestedHoliday() {
        when(repository.forChat(CHAT)).thenReturn(List.of(
                new ContactNotificationRuleRepository.Rule("MARKER", "JC", "WORK_ONLY", "NORMAL")));
        when(policies.forChat(CHAT, "WORK_ONLY")).thenReturn(workWindow());
        Instant opening = Instant.parse("2026-11-02T11:00:00Z"); // Monday, 08:00 BRT
        Instant closing = Instant.parse("2026-11-02T15:00:00Z"); // 12:00 BRT
        assertTrue(rules.route(CHAT, "Equipe (jç)", false, false, false, opening).allowed());
        assertFalse(rules.route(CHAT, "Equipe (JC)", false, false, false, closing).allowed());

        var holiday = new HolidayEntry(LocalDate.of(2026, 11, 2), "BR", "Synthetic",
                "https://example.org/official", 2026, ValidationStatus.VALIDATED,
                false, "reviewer", opening);
        when(holidays.on(LocalDate.of(2026, 11, 2))).thenReturn(List.of(holiday));
        assertFalse(rules.route(CHAT, "Equipe JC", false, false, false, opening).allowed());
    }

    @Test void explicitContactPrecedesMarkerAndPriorityNeverAffectsUrgency() {
        when(repository.forChat(CHAT)).thenReturn(List.of(
                new ContactNotificationRuleRepository.Rule("MARKER", "CH", "WORK_ONLY", "PRIORITY"),
                new ContactNotificationRuleRepository.Rule("CONTACT", CHAT, "ANYTIME", "NORMAL")));
        when(policies.forChat(CHAT, "ANYTIME")).thenReturn(anytime());
        var route = rules.route(CHAT, "Chefia CH", false, false, false,
                Instant.parse("2026-11-02T15:00:00Z"));
        assertTrue(route.allowed());
        assertEquals("ANYTIME", route.profile());
        assertFalse(route.priority(), "contact-specific NORMAL overrides marker PRIORITY");

        when(repository.forChat(CHAT)).thenReturn(List.of(
                new ContactNotificationRuleRepository.Rule("MARKER", "CH", null, "PRIORITY")));
        when(policies.forChat(CHAT, "ANYTIME")).thenReturn(anytime());
        var priority = rules.route(CHAT, "Ch CH", false, false, false,
                Instant.parse("2026-11-02T15:00:00Z"));
        assertTrue(priority.priority());
        assertEquals("ANYTIME", priority.profile());
    }

    @Test void overlappingMarkersChooseRestrictiveProfileAndDoNotIncludeGroups() {
        when(repository.forChat(CHAT)).thenReturn(List.of(
                new ContactNotificationRuleRepository.Rule("MARKER", "JC", "WORK_ONLY", "NORMAL"),
                new ContactNotificationRuleRepository.Rule("MARKER", "CH", "ANYTIME", "PRIORITY")));
        when(policies.forChat(CHAT, "WORK_ONLY")).thenReturn(workWindow());
        var route = rules.route(CHAT, "(JC) CH", false, false, false,
                Instant.parse("2026-11-02T15:00:00Z"));
        assertEquals("WORK_ONLY", route.profile());
        assertTrue(route.priority());
        assertFalse(route.allowed());

        var group = rules.route("123@g.us", "(JC) CH", true, false, false, Instant.now());
        assertEquals("ANYTIME", group.profile());
        assertFalse(group.priority());
    }

    private static EffectivePolicy workWindow() {
        return new PolicyResolver().resolve(new GlobalPolicy(Map.of(PolicyField.INCLUDE_OPTIONAL_HOLIDAYS, false)),
                new Profile("WORK_ONLY", Map.of(PolicyField.MODE, ScheduleMode.WORK_WINDOW,
                        PolicyField.WORK_DAYS, Set.of(DayOfWeek.MONDAY),
                        PolicyField.WINDOWS, List.of(new WorkWindow(LocalTime.of(8,0),LocalTime.NOON)))),
                ChatOverride.empty());
    }

    private static EffectivePolicy anytime() {
        return new PolicyResolver().resolve(new GlobalPolicy(Map.of(PolicyField.INCLUDE_OPTIONAL_HOLIDAYS, false)),
                new Profile("ANYTIME", Map.of(PolicyField.MODE, ScheduleMode.ANYTIME)),
                ChatOverride.empty());
    }
}
