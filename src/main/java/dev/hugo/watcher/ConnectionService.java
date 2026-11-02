package dev.hugo.watcher;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ConnectionService {
    static final String ACTION_TYPE = "whatsapp_connection_lost";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter UTC_ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC);

    private final ConnectionStateRepository states;
    private final TransactionTemplate transactions;
    private final NotificationOutboxRepository outbox;
    private final WatcherProperties properties;
    private final Duration gracePeriod;

    @Autowired
    public ConnectionService(ConnectionStateRepository states, PlatformTransactionManager manager,
                             NotificationOutboxRepository outbox, WatcherProperties properties) {
        this(states, manager, outbox, properties, Duration.ofSeconds(60));
    }

    /** Legacy explicit-construction adapter retained for integration tests. */
    public ConnectionService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                             NotificationOutboxRepository outbox, WatcherProperties properties) {
        this(new ConnectionStateRepository(jdbc), manager, outbox, properties, Duration.ofSeconds(60));
    }

    ConnectionService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                      NotificationOutboxRepository outbox, WatcherProperties properties, Duration gracePeriod) {
        this(new ConnectionStateRepository(jdbc), manager, outbox, properties, gracePeriod);
    }

    private ConnectionService(ConnectionStateRepository states, PlatformTransactionManager manager,
                              NotificationOutboxRepository outbox, WatcherProperties properties, Duration gracePeriod) {
        this.states = states;
        this.transactions = new TransactionTemplate(manager);
        this.outbox = outbox;
        this.properties = properties;
        this.gracePeriod = gracePeriod;
    }

    public void onState(ConnectionEvent event) {
        if (event == null || event.state() == null) return;
        Instant at = event.occurredAt() == null ? Instant.now() : event.occurredAt();
        transactions.executeWithoutResult(status -> {
            states.ensureState();
            var current = states.readStateForUpdate();
            if (event.state() == ConnectionEvent.State.CONNECTED) {
                states.markConnected();
                outbox.cancelPendingConnectionAlerts(at);
                return;
            }
            boolean pairing = event.state() == ConnectionEvent.State.DEVICE_REMOVED
                    || event.state() == ConnectionEvent.State.MANUAL_PAIRING_REQUIRED;
            if ("DISCONNECTED".equals(current.connectionState())
                    && (!pairing || current.manualPairingRequired() || current.alerted())) return;
            long generation = current.generation() + 1;
            String reason = event.reason() == null || event.reason().isBlank()
                    ? pairing ? "device_removed" : "connection_lost" : event.reason();
            states.startOutage(generation, at, reason, event.statusCode(), pairing);
            if (pairing) {
                enqueueAlert(generation, at, reason, event.statusCode(), true);
                states.markAlerted();
            }
        });
    }

    /** Called by the scheduled flush so an outage that survives restart still receives its grace-period alert. */
    public void flushGracePeriod(Instant now) {
        if (now == null) return;
        transactions.executeWithoutResult(status -> {
            states.ensureState();
            var current = states.readStateForUpdate();
            if (!"DISCONNECTED".equals(current.connectionState()) || current.alerted() || current.manualPairingRequired()
                    || current.outageStartedAt() == null || now.isBefore(current.outageStartedAt().plus(gracePeriod))) return;
            enqueueAlert(current.generation(), now, current.reason(), current.statusCode(), false);
            states.markAlertedIfNotAlready();
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

}
