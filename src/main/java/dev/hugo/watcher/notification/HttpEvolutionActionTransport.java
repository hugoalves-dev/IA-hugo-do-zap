package dev.hugo.watcher.notification;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Evolution sendText client. HTTP success is accepted by Evolution, not proof of WhatsApp delivery. */
public class HttpEvolutionActionTransport implements EvolutionActionTransport {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    // Only this exact Docker Compose service endpoint may use cleartext HTTP.
    private static final URI TRUSTED_INTERNAL_HTTP_ENDPOINT = URI.create("http://evolution-api:8080");
    private final EvolutionActionProperties properties;
    private final HttpClient http;

    public HttpEvolutionActionTransport(EvolutionActionProperties properties) {
        this.properties = properties;
        validateConfiguration(properties);
        this.http = HttpClient.newBuilder().connectTimeout(properties.getTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public EvolutionSendResult send(String chatJid, String text, String idempotencyKey) {
        try {
            String path = "/message/sendText/" + URLEncoder.encode(properties.getInstance(), StandardCharsets.UTF_8).replace("+", "%20");
            URI endpoint = properties.getBaseUrl().resolve(ensureSlash(properties.getBaseUrl().getPath()) + path.substring(1));
            String payload = JSON.writeValueAsString(Map.of("number", chatJid, "text", text));
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(properties.getTimeout())
                    .header("Content-Type", "application/json")
                    .header("apikey", properties.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400 && response.statusCode() < 500) return EvolutionSendResult.failed();
            if (response.statusCode() < 200 || response.statusCode() >= 300) return EvolutionSendResult.unknown();
            // Evolution acceptance is not a delivery receipt. No authenticated receipt contract is wired yet.
            return EvolutionSendResult.unknown();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return EvolutionSendResult.unknown();
        } catch (Exception timeoutOrProtocolFailure) {
            return EvolutionSendResult.unknown();
        }
    }

    /**
     * Fail fast without ever interpolating configuration values into exceptions or logs.
     * Plain HTTP remains restricted to the exact internal Docker service URI.
     */
    static void validateConfiguration(EvolutionActionProperties properties) {
        if (properties == null) throw invalid("watcher.whatsapp-actions.evolution");
        URI base = properties.getBaseUrl();
        if (base == null || base.getHost() == null || base.getHost().isBlank()
                || (!"https".equalsIgnoreCase(base.getScheme())
                    && !TRUSTED_INTERNAL_HTTP_ENDPOINT.equals(base))
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null)
            throw invalid("watcher.whatsapp-actions.evolution.base-url");

        if (properties.getInstance() == null || properties.getInstance().isBlank())
            throw invalid("watcher.whatsapp-actions.evolution.instance");

        if (properties.getApiKey() == null || properties.getApiKey().isBlank())
            throw invalid("watcher.whatsapp-actions.evolution.api-key");

        Duration timeout = properties.getTimeout();
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofMinutes(2)) > 0)
            throw invalid("watcher.whatsapp-actions.evolution.timeout");
    }

    private static IllegalArgumentException invalid(String property) {
        return new IllegalArgumentException("Invalid Evolution action transport configuration: " + property);
    }

    private static String ensureSlash(String path) { return path.endsWith("/") ? path : path + "/"; }
}
