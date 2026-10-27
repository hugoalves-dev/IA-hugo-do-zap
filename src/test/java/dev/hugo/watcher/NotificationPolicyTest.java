package dev.hugo.watcher;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NotificationPolicyTest {
    @Test void recognizesNamesWithoutTreatingIdentifiersAsNames() {
        assertEquals(NotificationPolicy.Kind.WORK_ONLY, NotificationPolicy.classify("José JC"));
        assertEquals(NotificationPolicy.Kind.ANYTIME, NotificationPolicy.classify("JC123"));
        assertEquals(NotificationPolicy.Kind.ANYTIME, NotificationPolicy.classify("123@lid"));
    }
    @Test void respectsLocalWeekdayAndExclusiveEndOfWindow() {
        var zone = ZoneId.of("America/Sao_Paulo");
        var window = new NotificationPolicy.Window(LocalTime.of(8,0), LocalTime.of(12,0));
        assertTrue(NotificationPolicy.isWithinWorkHours(Instant.parse("2026-10-26T11:00:00Z"), zone, Set.of(DayOfWeek.MONDAY), List.of(window)));
        assertFalse(NotificationPolicy.isWithinWorkHours(Instant.parse("2026-10-26T15:00:00Z"), zone, Set.of(DayOfWeek.MONDAY), List.of(window)));
    }
}
