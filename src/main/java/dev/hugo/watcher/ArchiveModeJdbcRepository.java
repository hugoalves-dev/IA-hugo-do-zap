package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;

/** Legacy standalone-test adapter; SQL stays in the repository layer. */
final class ArchiveModeJdbcRepository {
    private final JdbcTemplate jdbc;

    ArchiveModeJdbcRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    String readMode() {
        return jdbc.queryForObject("select mode from archive_monitor_settings where singleton_id=1", String.class);
    }

    int updateMode(String mode) {
        return jdbc.update("update archive_monitor_settings set mode=?,updated_at=now() where singleton_id=1", mode);
    }
}
