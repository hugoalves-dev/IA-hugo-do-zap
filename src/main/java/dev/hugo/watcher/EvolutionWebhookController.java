package dev.hugo.watcher;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
public class EvolutionWebhookController {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final InboxRepository inbox;
    private final EvolutionWebhookProperties properties;
    private final ConnectionService connections;

    public EvolutionWebhookController(InboxRepository inbox, EvolutionWebhookProperties properties,
                                      ConnectionService connections) {
        this.inbox = inbox;
        this.properties = properties;
        this.connections = connections;
    }

    @PostMapping(value = "/internal/evolution/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    @SuppressWarnings("unchecked")
    public ResponseEntity<Void> receive(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                        @RequestBody String body) {
        if (!authorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                    .build();
        }

        final Map<String, Object> payload;
        try {
            payload = (Map<String, Object>) JSON.readValue(body, Map.class);
        } catch (RuntimeException ex) {
            return ResponseEntity.badRequest().build();
        }
        if (payload == null) return ResponseEntity.badRequest().build();

        String instance = stringValue(payload.get("instance"));
        if (!properties.getInstance().equals(instance)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        String eventType = stringValue(payload.get("event"));
        if ("connection.update".equals(eventType)) {
            try {
                return receiveConnectionUpdate(payload);
            } catch (DataAccessException ex) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
        }
        String externalId = externalId(payload);
        if ("contacts.upsert".equals(eventType) || "contacts.update".equals(eventType)) {
            // Evolution contact events have no stable event ID; every observation must remain replayable.
            externalId = java.util.UUID.randomUUID().toString();
        }
        if (eventType != null && eventType.startsWith("chats.")) {
            try {
                inbox.quarantine("CHAT_STATE", null, "unverified_or_incomplete_state");
                return ResponseEntity.accepted().build();
            } catch (DataAccessException ex) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
        }
        if (eventType == null || eventType.isBlank() || externalId == null || externalId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        try {
            Acceptance acceptance = inbox.accept(new EvolutionEvent("evolution", instance, eventType, externalId, payload));
            return ResponseEntity.status(acceptance == Acceptance.NEW ? HttpStatus.ACCEPTED : HttpStatus.OK).build();
        } catch (DataAccessException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    private ResponseEntity<Void> receiveConnectionUpdate(Map<String, Object> payload) {
        Object value = payload.get("data");
        if (!(value instanceof Map<?, ?> data)) return ResponseEntity.badRequest().build();
        String state = stringValue(data.get("state"));
        if (state == null || state.isBlank()) return ResponseEntity.badRequest().build();
        Integer statusReason = integerValue(data.get("statusReason"));
        if ("open".equals(state)) {
            connections.onState(new ConnectionEvent(ConnectionEvent.State.CONNECTED, null, "open", statusReason));
            return ResponseEntity.ok().build();
        }
        if ("close".equals(state)) {
            boolean loggedOut = Integer.valueOf(401).equals(statusReason);
            ConnectionEvent.State mapped = loggedOut
                    ? ConnectionEvent.State.MANUAL_PAIRING_REQUIRED : ConnectionEvent.State.DISCONNECTED;
            connections.onState(new ConnectionEvent(mapped, null, loggedOut ? "logged_out" : "connection_lost", statusReason));
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.ok().build();
    }

    private static Integer integerValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try { return Integer.valueOf(text); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private boolean authorized(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return false;
        byte[] supplied = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
        byte[] expected = properties.getWebhookToken().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, supplied);
    }

    private static String externalId(Map<String, Object> payload) {
        Object dataValue = payload.get("data");
        if (dataValue instanceof Map<?, ?> data) {
            Object keyValue = data.get("key");
            if (keyValue instanceof Map<?, ?> key) {
                String keyId = stringValue(key.get("id"));
                if (keyId != null && !keyId.isBlank()) return keyId;
            }
        }
        return stringValue(payload.get("id"));
    }

    private static String stringValue(Object value) {
        return value instanceof String text ? text : null;
    }
}
