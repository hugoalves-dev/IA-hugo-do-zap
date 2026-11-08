package dev.hugo.watcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Full durable Telegram delivery payload transported by RabbitMQ; PostgreSQL supplies only delivery state. */
public record RabbitDeliveryJob(String actionKey, String actionType, String payload, String pendingSnapshot) {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public RabbitDeliveryJob {
        if (actionKey == null || actionKey.isBlank()) throw new IllegalArgumentException("action key is required");
        if (actionType == null || actionType.isBlank()) throw new IllegalArgumentException("action type is required");
        if (payload == null || payload.isBlank()) throw new IllegalArgumentException("action payload is required");
        if (pendingSnapshot == null || pendingSnapshot.isBlank()) pendingSnapshot = "[]";
    }

    public String toJson() {
        try { return JSON.writeValueAsString(this); }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("delivery job is not serializable", ex); }
    }

    public static RabbitDeliveryJob parse(String body) {
        try {
            RabbitDeliveryJob job = JSON.readValue(body, RabbitDeliveryJob.class);
            if (job == null) throw new IllegalArgumentException("delivery job is empty");
            return job;
        } catch (JsonProcessingException ex) {
            // Rolling upgrades can still consume the previous queue contract (the action key only).
            if (body != null && body.matches("[A-Za-z0-9:_./-]{1,255}")) return null;
            throw new IllegalArgumentException("invalid RabbitMQ delivery job", ex);
        }
    }
}
