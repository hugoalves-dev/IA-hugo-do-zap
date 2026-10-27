package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ConnectionService {
    private static final String ACTION_TYPE = "whatsapp_connection_lost";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter UTC_ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final NotificationOutboxRepository outbox;
    private final WatcherProperties properties;
    private final Duration gracePeriod;

    @Autowired
    public ConnectionService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                             NotificationOutboxRepository outbox, WatcherProperties properties) {
        this(jdbc, manager, outbox, properties, Duration.ofSeconds(60));
    }

    ConnectionService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                      NotificationOutboxRepository outbox, WatcherProperties properties, Duration gracePeriod) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.outbox = outbox;
        this.properties = properties;
        this.gracePeriod = gracePeriod;
    }

    public void onState(ConnectionEvent event) {
        if (event == null || event.state() == null) return;
        Instant at = event.occurredAt() == null ? Instant.now() : event.occurredAt();
        transactions.executeWithoutResult(status -> {
            ensureState();
            StateRow current = readStateForUpdate();
            if (event.state() == ConnectionEvent.State.CONNECTED) {
                jdbc.update("""
                        update watcher_connection_state set connected=true,outage_started_at=null,reason=null,
                            status_code=null,manual_pairing_required=false,alerted=false where singleton_id=1
                        """);
                return;
            }
            boolean pairing = event.state() == ConnectionEvent.State.DEVICE_REMOVED
                    || event.state() == ConnectionEvent.State.MANUAL_PAIRING_REQUIRED;
            if (!current.connected() && (!pairing || current.manualPairingRequired() || current.alerted())) return;
            long generation = current.generation() + 1;
            String reason = event.reason() == null || event.reason().isBlank()
                    ? pairing ? "device_removed" : "connection_lost" : event.reason();
            jdbc.update("""
                    update watcher_connection_state set connected=false,outage_generation=?,outage_started_at=?,
                        reason=?,status_code=?,manual_pairing_required=?,alerted=false where singleton_id=1
                    """, generation, Timestamp.from(at), reason, event.statusCode(), pairing);
            if (pairing) {
                enqueueAlert(generation, at, reason, event.statusCode(), true);
                jdbc.update("update watcher_connection_state set alerted=true where singleton_id=1");
            }
        });
    }

    /** Called by the scheduled flush so an outage that survives restart still receives its grace-period alert. */
    public void flushGracePeriod(Instant now) {
        if (now == null) return;
        transactions.executeWithoutResult(status -> {
            ensureState();
            StateRow current = readStateForUpdate();
            if (current.connected() || current.alerted() || current.manualPairingRequired()
                    || current.outageStartedAt() == null || now.isBefore(current.outageStartedAt().plus(gracePeriod))) return;
            enqueueAlert(current.generation(), now, current.reason(), current.statusCode(), false);
            jdbc.update("update watcher_connection_state set alerted=true where singleton_id=1 and alerted=false");
        });
    }

    private void enqueueAlert(long generation, Instant generatedAt, String reason, Integer statusCode, boolean pairing) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", ACTION_TYPE);
        payload.put("generatedAt", UTC_ISO.format(generatedAt));
        payload.put("reason", reason);
        if (statusCode != null) payload.put("statusCode", statusCode);
        payload.put("manualPairingRequired", pairing);
        String json;
        try { json = JSON.writeValueAsString(payload); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Could not encode connection alert", ex); }
        outbox.enqueue(ACTION_TYPE + ":" + generation, ACTION_TYPE, json, "[]", generatedAt,
                generatedAt.plusSeconds(7 * 86_400L));
    }

    private void ensureState() {
        jdbc.update("insert into watcher_connection_state(singleton_id) values (1) on conflict (singleton_id) do nothing");
    }

    private StateRow readStateForUpdate() {
        List<StateRow> rows = jdbc.query("""
                select connected,outage_generation,outage_started_at,reason,status_code,manual_pairing_required,alerted
                from watcher_connection_state where singleton_id=1 for update
                """, (rs, row) -> new StateRow(rs.getBoolean("connected"), rs.getLong("outage_generation"),
                rs.getTimestamp("outage_started_at") == null ? null : rs.getTimestamp("outage_started_at").toInstant(),
                rs.getString("reason"), (Integer) rs.getObject("status_code"),
                rs.getBoolean("manual_pairing_required"), rs.getBoolean("alerted")));
        return rows.getFirst();
    }

    private record StateRow(boolean connected, long generation, Instant outageStartedAt, String reason,
                            Integer statusCode, boolean manualPairingRequired, boolean alerted) { }
}
