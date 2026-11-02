package dev.hugo.watcher.policy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * Read-only SQL projection. Holiday_calendar has a composite PK (date, jurisdiction, name);
 * do not invent a fake single-column @Id or expose unsafe CrudRepository.save().
 */
@Repository
public class HolidayCalendarQueryRepository {
    private final JdbcTemplate jdbc;

    public HolidayCalendarQueryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<HolidayEntry> on(LocalDate date) {
        return jdbc.query("""
                select local_date, jurisdiction, name, source, calendar_year, validation_status, optional,
                       validated_by, validated_at
                  from holiday_calendar
                 where local_date = ? and calendar_year = ? and jurisdiction in ('BR', 'SP', 'LINS')
                 order by case jurisdiction when 'LINS' then 1 when 'SP' then 2 else 3 end
                """, (rs, row) -> new HolidayEntry(rs.getObject("local_date", LocalDate.class),
                rs.getString("jurisdiction"), rs.getString("name"), rs.getString("source"),
                rs.getInt("calendar_year"), ValidationStatus.valueOf(rs.getString("validation_status")),
                rs.getBoolean("optional"), rs.getString("validated_by"),
                rs.getTimestamp("validated_at") == null ? null : rs.getTimestamp("validated_at").toInstant()),
                date, date.getYear());
    }
}
