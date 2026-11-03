package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;

/** Legacy explicit-construction test adapter; keep all SQL in repositories. */
final class OperationalRestrictionJdbcRepository {
    private final JdbcTemplate jdbc;
    OperationalRestrictionJdbcRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    Boolean emergencyLatched() {
        return jdbc.queryForObject("select emergency_latched from operational_restrictions where singleton_id=1", Boolean.class);
    }
    Boolean simulationPaused() {
        return jdbc.queryForObject("select simulation_paused from operational_restrictions where singleton_id=1", Boolean.class);
    }
}
