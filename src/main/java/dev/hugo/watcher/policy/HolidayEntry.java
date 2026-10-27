package dev.hugo.watcher.policy;

import java.time.LocalDate;

public record HolidayEntry(LocalDate date, String jurisdiction, String name, String source,
                           int calendarYear, ValidationStatus validationStatus, boolean optional) {
    public boolean verified() {
        return validationStatus == ValidationStatus.VALIDATED && source != null && !source.isBlank()
                && calendarYear == date.getYear();
    }
}
