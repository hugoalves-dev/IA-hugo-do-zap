package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * PostgreSQL-native transaction participant for the connection singleton.
 * SELECT FOR UPDATE and the following writes must share the service transaction
 * with the notification outbox. Neither save() nor separate transactions are safe here.
 */
@Repository
public class ConnectionStateRepository {
    private final JdbcTemplate jdbc;

    public ConnectionStateRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void ensureState() {
        jdbc.update("insert into watcher_connection_state(singleton_id) values (1) on conflict (singleton_id) do nothing");
    }

    public StateRow readStateForUpdate() {
        List<StateRow> rows = jdbc.query("""
                select connection_state,outage_generation,outage_started_at,reason,status_code,manual_pairing_required,alerted
                  from watcher_connection_state where singleton_id=1 for update
                """, (rs, row) -> new StateRow(rs.getString("connection_state"), rs.getLong("outage_generation"),
                rs.getTimestamp("outage_started_at") == null ? null : rs.getTimestamp("outage_started_at").toInstant(),
                rs.getString("reason"), (Integer) rs.getObject("status_code"),
                rs.getBoolean("manual_pairing_required"), rs.getBoolean("alerted")));
        return rows.getFirst();
    }

    public void markConnected() {
        jdbc.update("""
                update watcher_connection_state set connection_state='CONNECTED',outage_started_at=null,reason=null,
                    status_code=null,manual_pairing_required=false,alerted=false where singleton_id=1
                """);
    }

    public void startOutage(long generation, Instant at, String reason, Integer statusCode, boolean pairing) {
        jdbc.update("""
                update watcher_connection_state set connection_state='DISCONNECTED',outage_generation=?,outage_started_at=?,
                    reason=?,status_code=?,manual_pairing_required=?,alerted=false where singleton_id=1
                """, generation, Timestamp.from(at), reason, statusCode, pairing);
    }

    public void markAlerted() {
        jdbc.update("update watcher_connection_state set alerted=true where singleton_id=1");
    }

    public void markAlertedIfNotAlready() {
        jdbc.update("update watcher_connection_state set alerted=true where singleton_id=1 and alerted=false");
    }

    public record StateRow(String connectionState, long generation, Instant outageStartedAt, String reason,
                           Integer statusCode, boolean manualPairingRequired, boolean alerted) { }
}
