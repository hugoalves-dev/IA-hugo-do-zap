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

    private static void validateConfiguration(EvolutionActionProperties properties) {
        URI base = properties.getBaseUrl();
        if (base == null || !"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || properties.getInstance() == null || properties.getInstance().isBlank()
                || properties.getApiKey() == null || properties.getApiKey().isBlank()
                || properties.getTimeout() == null || properties.getTimeout().isNegative()
                || properties.getTimeout().isZero() || properties.getTimeout().compareTo(Duration.ofMinutes(2)) > 0)
            throw new IllegalArgumentException("Evolution action transport configuration is invalid");
    }

    private static String ensureSlash(String path) { return path.endsWith("/") ? path : path + "/"; }
}
