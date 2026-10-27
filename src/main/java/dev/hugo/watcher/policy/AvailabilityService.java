package dev.hugo.watcher.policy;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class AvailabilityService {
    private final HolidayService holidays;

    public AvailabilityService(HolidayService holidays) { this.holidays = Objects.requireNonNull(holidays); }

    public Availability at(Instant instant, ZoneId zone, EffectivePolicy policy) {
        Objects.requireNonNull(instant, "instant");
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(policy, "policy");
        TemporaryAvailabilityOverride temporary = policy.temporaryAvailability();
        if (temporary != null && temporary.appliesAt(instant)) {
            return new Availability(temporary.state(), AvailabilitySource.TEMPORARY_OVERRIDE, "temporary override");
        }

        boolean includeOptional = Boolean.TRUE.equals(policy.value(PolicyField.INCLUDE_OPTIONAL_HOLIDAYS));
        for (HolidayEntry holiday : holidays.on(instant.atZone(zone).toLocalDate())) {
            if (!holiday.hasManualAttestation() || (holiday.optional() && !includeOptional)) continue;
            return new Availability(AvailabilityState.UNAVAILABLE,
                    holiday.optional() ? AvailabilitySource.OPTIONAL_HOLIDAY : AvailabilitySource.VALIDATED_HOLIDAY,
                    holiday.name());
        }

        ScheduleMode mode = (ScheduleMode) policy.value(PolicyField.MODE);
        if (mode == ScheduleMode.ANYTIME) {
            return new Availability(AvailabilityState.AVAILABLE, AvailabilitySource.ANYTIME, "anytime profile");
        }
        if (mode == null || !(policy.value(PolicyField.WORK_DAYS) instanceof Set<?> rawDays)
                || !(policy.value(PolicyField.WINDOWS) instanceof List<?> rawWindows)) {
            return new Availability(AvailabilityState.UNAVAILABLE, AvailabilitySource.NO_POLICY, "schedule is incomplete");
        }
        var local = instant.atZone(zone);
        boolean workDay = rawDays.contains(local.getDayOfWeek());
        boolean inWindow = rawWindows.stream().map(WorkWindow.class::cast).anyMatch(window -> window.contains(local.toLocalTime()));
        return workDay && inWindow
                ? new Availability(AvailabilityState.AVAILABLE, AvailabilitySource.WORK_WINDOW, "inside work window")
                : new Availability(AvailabilityState.UNAVAILABLE, AvailabilitySource.WORK_WINDOW, "outside work window");
    }
}
