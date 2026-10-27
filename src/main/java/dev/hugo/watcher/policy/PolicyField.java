package dev.hugo.watcher.policy;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Set;

public enum PolicyField {
    MODE(ScheduleMode.class),
    WORK_DAYS(Set.class),
    WINDOWS(List.class),
    INCLUDE_OPTIONAL_HOLIDAYS(Boolean.class);

    private final Class<?> type;

    PolicyField(Class<?> type) { this.type = type; }

    Object copyAndValidate(Object value) {
        if (value == null || !type.isInstance(value)) {
            throw new IllegalArgumentException(name() + " must be " + type.getSimpleName());
        }
        return switch (this) {
            case MODE, INCLUDE_OPTIONAL_HOLIDAYS -> value;
            case WORK_DAYS -> {
                Set<?> days = (Set<?>) value;
                if (days.stream().anyMatch(day -> !(day instanceof DayOfWeek))) {
                    throw new IllegalArgumentException("WORK_DAYS must contain DayOfWeek values");
                }
                yield Set.copyOf(days);
            }
            case WINDOWS -> {
                List<?> windows = (List<?>) value;
                if (windows.stream().anyMatch(window -> !(window instanceof WorkWindow))) {
                    throw new IllegalArgumentException("WINDOWS must contain WorkWindow values");
                }
                yield List.copyOf(windows);
            }
        };
    }
}
