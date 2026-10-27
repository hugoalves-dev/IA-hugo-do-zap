package dev.hugo.watcher.policy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class HolidayService {
    private final JdbcTemplate jdbc;

    public HolidayService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<HolidayEntry> on(LocalDate date) {
        return jdbc.query("""
                select local_date, jurisdiction, name, source, calendar_year, validation_status, optional
                  from holiday_calendar
                 where local_date = ? and calendar_year = ? and jurisdiction in ('BR', 'SP', 'LINS')
                 order by case jurisdiction when 'LINS' then 1 when 'SP' then 2 else 3 end
                """, (rs, row) -> new HolidayEntry(rs.getObject("local_date", LocalDate.class),
                rs.getString("jurisdiction"), rs.getString("name"), rs.getString("source"),
                rs.getInt("calendar_year"), ValidationStatus.valueOf(rs.getString("validation_status")),
                rs.getBoolean("optional")), date, date.getYear());
    }
}
