package dev.hugo.watcher.policy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class GlobalPolicyRepository {
    private final JdbcTemplate jdbc;

    public GlobalPolicyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<TemporaryAvailabilityOverride> temporaryAvailabilityOverride() {
        List<TemporaryAvailabilityOverride> rows = jdbc.query("""
                select state, starts_at, expires_at
                  from availability_overrides
                 where singleton_id = 1
                """, (rs, row) -> new TemporaryAvailabilityOverride(
                AvailabilityState.valueOf(rs.getString("state")), rs.getTimestamp("starts_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant()));
        return rows.stream().findFirst();
    }

    public void saveTemporaryAvailabilityOverride(TemporaryAvailabilityOverride override) {
        Objects.requireNonNull(override, "override");
        jdbc.update("""
                insert into availability_overrides(singleton_id, state, starts_at, expires_at, updated_at)
                values (1, ?, ?, ?, now())
                on conflict (singleton_id) do update
                    set state=excluded.state, starts_at=excluded.starts_at, expires_at=excluded.expires_at, updated_at=now()
                """, override.state().name(), Timestamp.from(override.startsAt()), Timestamp.from(override.expiresAt()));
    }
}
