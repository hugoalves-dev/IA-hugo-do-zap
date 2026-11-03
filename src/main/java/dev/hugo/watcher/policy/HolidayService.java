package dev.hugo.watcher.policy;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public class HolidayService {
    private final HolidayCalendarQueryRepository calendar;

    public HolidayService(HolidayCalendarQueryRepository calendar) {
        this.calendar = Objects.requireNonNull(calendar);
    }

    /** Legacy explicit construction retained for current integration tests. */
    public HolidayService(JdbcTemplate jdbc) { this(new HolidayCalendarQueryRepository(jdbc)); }

    public List<HolidayEntry> on(LocalDate date) { return calendar.on(date); }
}
