package dev.hugo.watcher.policy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
class AvailabilityServiceTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private AvailabilityService availability;
    private GlobalPolicyRepository globalPolicies;
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    @BeforeEach void migrateAndClearCalendar() {
        var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).load().migrate();
        jdbc.update("delete from holiday_calendar");
        jdbc.update("delete from availability_overrides");
        availability = new AvailabilityService(new HolidayService(jdbc));
        globalPolicies = new GlobalPolicyRepository(jdbc);
    }

    @Test void expiredTemporaryOverrideFallsThroughToWorkWindow() {
        var policy = policy(false, new TemporaryAvailabilityOverride(
                AvailabilityState.UNAVAILABLE, Instant.parse("2026-10-27T12:00:00Z"),
                Instant.parse("2026-10-27T13:00:00Z")));

        Availability result = availability.at(Instant.parse("2026-10-27T13:00:00Z"), SAO_PAULO, policy);

        assertEquals(AvailabilityState.AVAILABLE, result.state());
        assertEquals(AvailabilitySource.WORK_WINDOW, result.source());
    }

    @Test void optionalHolidayClosesOnlyWhenPolicyOptsIn() {
        insertHoliday(LocalDate.of(2026, 10, 27), "SP", "Fixture optional day", "https://official.example/2026", 2026, "VALIDATED", true);
        Instant duringWork = Instant.parse("2026-10-27T13:00:00Z");

        assertEquals(AvailabilitySource.WORK_WINDOW, availability.at(duringWork, SAO_PAULO, policy(false, null)).source());
        Availability enabled = availability.at(duringWork, SAO_PAULO, policy(true, null));
        assertEquals(AvailabilityState.UNAVAILABLE, enabled.state());
        assertEquals(AvailabilitySource.OPTIONAL_HOLIDAY, enabled.source());
    }

    @Test void activeTemporaryOverrideTakesPrecedenceOverValidatedHoliday() {
        insertHoliday(LocalDate.of(2026, 10, 27), "SP", "Validated fixture", "https://official.example/2026", 2026, "VALIDATED", false);
        var temporary = new TemporaryAvailabilityOverride(
                AvailabilityState.AVAILABLE, Instant.parse("2026-10-27T12:00:00Z"),
                Instant.parse("2026-10-27T14:00:00Z"));
        globalPolicies.saveTemporaryAvailabilityOverride(temporary);
        var policy = policy(false, globalPolicies.temporaryAvailabilityOverride().orElseThrow());

        Availability result = availability.at(Instant.parse("2026-10-27T12:00:00Z"), SAO_PAULO, policy);

        assertEquals(AvailabilityState.AVAILABLE, result.state());
        assertEquals(AvailabilitySource.TEMPORARY_OVERRIDE, result.source());
        assertEquals(1, jdbc.queryForObject("select count(*) from availability_overrides where singleton_id=1", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from chat_policy_overrides", Integer.class));
    }

    @Test void validatedStateAndMunicipalHolidaysApplyWithinTheirCalendarYear() {
        insertHoliday(LocalDate.of(2026, 12, 31), "SP", "State fixture", "https://official.example/sp/2026", 2026, "VALIDATED", false);
        insertHoliday(LocalDate.of(2027, 1, 1), "LINS", "Municipal fixture", "https://lins.example/2027", 2027, "VALIDATED", false);

        Availability stateHoliday = availability.at(Instant.parse("2026-12-31T13:00:00Z"), SAO_PAULO, policy(false, null));
        Availability municipalHoliday = availability.at(Instant.parse("2027-01-01T13:00:00Z"), SAO_PAULO, policy(false, null));

        assertEquals(AvailabilitySource.VALIDATED_HOLIDAY, stateHoliday.source());
        assertEquals(AvailabilitySource.VALIDATED_HOLIDAY, municipalHoliday.source());
        assertEquals("Municipal fixture", municipalHoliday.reason());
    }

    @Test void holidayWithoutVerifiedSourceFallsBackToSchedule() {
        insertHoliday(LocalDate.of(2026, 10, 27), "LINS", "Unverified fixture", null, 2026, "UNKNOWN", false);

        Availability result = availability.at(Instant.parse("2026-10-27T13:00:00Z"), SAO_PAULO, policy(false, null));

        assertEquals(AvailabilityState.AVAILABLE, result.state());
        assertEquals(AvailabilitySource.WORK_WINDOW, result.source());
    }

    @Test void holidayDateUsesSaoPauloCalendarAcrossUtcMidnight() {
        insertHoliday(LocalDate.of(2026, 10, 27), "SP", "Timezone fixture", "https://official.example/sp/2026", 2026, "VALIDATED", false);

        Availability result = availability.at(Instant.parse("2026-10-28T02:00:00Z"), SAO_PAULO, policy(false, null));

        assertEquals(AvailabilitySource.VALIDATED_HOLIDAY, result.source());
        assertEquals("Timezone fixture", result.reason());
    }

    private void insertHoliday(LocalDate day, String jurisdiction, String name, String source, int year, String status, boolean optional) {
        String validatedBy = "VALIDATED".equals(status) ? "fixture-maintainer" : null;
        java.sql.Timestamp validatedAt = "VALIDATED".equals(status)
                ? java.sql.Timestamp.from(Instant.parse("2026-10-27T12:00:00Z")) : null;
        jdbc.update("""
                insert into holiday_calendar(local_date, jurisdiction, name, source, calendar_year, validation_status,
                                            optional, validated_by, validated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, day, jurisdiction, name, source, year, status, optional, validatedBy, validatedAt);
    }

    private EffectivePolicy policy(boolean includeOptional, TemporaryAvailabilityOverride temporary) {
        GlobalPolicy global = new GlobalPolicy(java.util.Map.of(
                PolicyField.MODE, ScheduleMode.WORK_WINDOW,
                PolicyField.WORK_DAYS, Set.of(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY,
                        java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.THURSDAY, java.time.DayOfWeek.FRIDAY),
                PolicyField.WINDOWS, List.of(new WorkWindow(LocalTime.of(8, 0), LocalTime.of(12, 0)),
                        new WorkWindow(LocalTime.of(13, 30), LocalTime.of(17, 30))),
                PolicyField.INCLUDE_OPTIONAL_HOLIDAYS, includeOptional), temporary);
        return new PolicyResolver().resolve(global, new Profile("default", java.util.Map.of()), new ChatOverride(java.util.Map.of()));
    }
}
