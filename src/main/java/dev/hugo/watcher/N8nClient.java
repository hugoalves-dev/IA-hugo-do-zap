package dev.hugo.watcher;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class N8nClient {
    private static final System.Logger LOG = System.getLogger(N8nClient.class.getName());
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final List<Duration> DEFAULT_RETRY_DELAYS = List.of(Duration.ofSeconds(1), Duration.ofSeconds(3));

    private final WatcherProperties properties;
    private final HttpClient http;
    private final Duration timeout;
    private final List<Duration> retryDelays;

    @Autowired
    public N8nClient(WatcherProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(), DEFAULT_TIMEOUT, DEFAULT_RETRY_DELAYS);
    }

    N8nClient(WatcherProperties properties, HttpClient http, Duration timeout, List<Duration> retryDelays) {
        this.properties = properties;
        this.http = http;
        this.timeout = timeout;
        this.retryDelays = List.copyOf(retryDelays);
    }

    /** Returns true only for a 2xx response; false leaves the durable action available for another flush. */
    public boolean send(OutboxAction action) {
        int attempts = retryDelays.size() + 1;
        String payload;
        try { payload = withCurrentSecret(action.payload()); }
        catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "n8n webhook payload is invalid: error={0}", ex.getClass().getSimpleName());
            return false;
        }
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getN8nUrl()))
                        .timeout(timeout)
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();
                HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 300) return true;
                LOG.log(System.Logger.Level.WARNING, "n8n webhook failed: status={0}, attempt={1}", response.statusCode(), attempt);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                LOG.log(System.Logger.Level.WARNING, "n8n webhook interrupted: attempt={0}", attempt);
                return false;
            } catch (IOException | RuntimeException ex) {
                LOG.log(System.Logger.Level.WARNING, "n8n webhook failed: error={0}, attempt={1}", ex.getClass().getSimpleName(), attempt);
            }
            if (attempt < attempts && !pause(retryDelays.get(attempt - 1))) return false;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private String withCurrentSecret(String storedPayload) {
        Map<String, Object> stored = JSON.readValue(storedPayload, Map.class);
        Map<String, Object> outbound = new LinkedHashMap<>();
        outbound.put("secret", properties.getN8nSecret());
        stored.forEach((key, value) -> { if (!"secret".equals(key)) outbound.put(key, value); });
        return JSON.writeValueAsString(outbound);
    }

    private static boolean pause(Duration delay) {
        if (delay.isZero() || delay.isNegative()) return true;
        try { Thread.sleep(delay.toMillis()); return true; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); return false; }
    }
}
