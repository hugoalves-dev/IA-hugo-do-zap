package dev.hugo.watcher.policy;

import java.time.LocalDate;
import java.time.Instant;

public record HolidayEntry(LocalDate date, String jurisdiction, String name, String source,
                           int calendarYear, ValidationStatus validationStatus, boolean optional,
                           String validatedBy, Instant validatedAt) {
    /** VALIDATED records a maintainer's manual attestation; this code does not fetch or verify the source. */
    public boolean hasManualAttestation() {
        return validationStatus == ValidationStatus.VALIDATED && source != null && !source.isBlank()
                && calendarYear == date.getYear() && validatedBy != null && !validatedBy.isBlank() && validatedAt != null;
    }
}
