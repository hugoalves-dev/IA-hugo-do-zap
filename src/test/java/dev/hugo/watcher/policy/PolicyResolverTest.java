package dev.hugo.watcher.policy;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolicyResolverTest {
    @Test void partiallyOverridesProfileAndRestoresInheritanceWhenFieldIsRemoved() {
        GlobalPolicy global = new GlobalPolicy(Map.of(
                PolicyField.MODE, ScheduleMode.ANYTIME,
                PolicyField.WORK_DAYS, Set.of(DayOfWeek.MONDAY),
                PolicyField.INCLUDE_OPTIONAL_HOLIDAYS, false));
        Profile profile = new Profile("WORK_ONLY", Map.of(
                PolicyField.MODE, ScheduleMode.WORK_WINDOW,
                PolicyField.WORK_DAYS, Set.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)));
        ChatOverride explicitOverride = new ChatOverride(Map.of(
                PolicyField.WORK_DAYS, Set.of(DayOfWeek.FRIDAY)));

        EffectivePolicy overridden = new PolicyResolver().resolve(global, profile, explicitOverride);
        assertEquals(Set.of(DayOfWeek.FRIDAY), overridden.value(PolicyField.WORK_DAYS));
        assertEquals(PolicySource.CHAT_OVERRIDE, overridden.origin(PolicyField.WORK_DAYS));
        assertEquals(ScheduleMode.WORK_WINDOW, overridden.value(PolicyField.MODE));
        assertEquals(PolicySource.PROFILE, overridden.origin(PolicyField.MODE));
        assertEquals(PolicySource.GLOBAL, overridden.origin(PolicyField.INCLUDE_OPTIONAL_HOLIDAYS));

        EffectivePolicy restored = new PolicyResolver().resolve(global, profile, ChatOverride.empty());
        assertEquals(Set.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), restored.value(PolicyField.WORK_DAYS));
        assertEquals(PolicySource.PROFILE, restored.origin(PolicyField.WORK_DAYS));
    }

    @Test void temporaryAvailabilityOverrideBelongsToGlobalPolicy() {
        var temporary = new TemporaryAvailabilityOverride(AvailabilityState.UNAVAILABLE,
                java.time.Instant.parse("2026-10-27T12:00:00Z"), java.time.Instant.parse("2026-10-27T14:00:00Z"));
        GlobalPolicy global = new GlobalPolicy(Map.<PolicyField, Object>of(PolicyField.MODE, ScheduleMode.ANYTIME), temporary);

        EffectivePolicy resolved = new PolicyResolver().resolve(global, new Profile("ANYTIME", Map.of()), ChatOverride.empty());

        assertEquals(temporary, resolved.temporaryAvailability());
    }
}
