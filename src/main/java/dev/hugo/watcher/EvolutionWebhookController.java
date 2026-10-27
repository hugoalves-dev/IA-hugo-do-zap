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

    public EvolutionWebhookController(InboxRepository inbox, EvolutionWebhookProperties properties) {
        this.inbox = inbox;
        this.properties = properties;
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
        String externalId = externalId(payload);
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
