package dev.hugo.watcher.policy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Service
public class HolidayService {
    private final HolidayCalendarQueryRepository calendar;

    @Autowired
    public HolidayService(HolidayCalendarQueryRepository calendar) {
        this.calendar = Objects.requireNonNull(calendar);
    }

    /** Legacy explicit construction retained for current integration tests. */
    public HolidayService(JdbcTemplate jdbc) { this(new HolidayCalendarQueryRepository(jdbc)); }

    public List<HolidayEntry> on(LocalDate date) { return calendar.on(date); }
}
