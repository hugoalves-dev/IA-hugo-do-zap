package dev.hugo.watcher.ai;

import tools.jackson.core.JacksonException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class OpenRouterProvider extends AbstractHttpClassificationProvider {
    private final URI endpoint;
    private final String model;
    private final String apiKey;

    public OpenRouterProvider(URI endpoint, String model, String apiKey, HttpClient http,
                              ProviderAuthorization authorization, Duration timeout) {
        super("openrouter", http, authorization, timeout);
        this.endpoint = ProviderEndpointPolicy.requireSafe(endpoint);
        this.model = requireText(model, "model");
        this.apiKey = requireText(apiKey, "apiKey");
    }

    @Override public ClassificationResult classify(ClassificationInput input) {
        java.util.Objects.requireNonNull(input, "input");
        if (input.messages().isEmpty()) return ClassificationResult.uncertain();
        authorize(input);
        ProviderResponse response = send(request(input));
        validateStatus(response.statusCode(), 404);
        return parseClassification(extractText(response.body()));
    }

    private HttpRequest request(ClassificationInput input) {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", userPayload(input))),
                "temperature", 0,
                "max_tokens", 300,
                "provider", Map.of("zdr", true, "data_collection", "deny", "allow_fallbacks", false,
                        "require_parameters", true),
                "response_format", Map.of("type", "json_schema", "json_schema", Map.of(
                        "name", "classification", "strict", true, "schema", SCHEMA)));
        try {
            return HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new ClassificationProviderException(ClassificationProviderException.Kind.CONFIGURATION_ERROR, exception);
        }
    }

    private String extractText(String body) {
        Map<?, ?> root = parseObject(body);
        Object choices = root.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty() || !(list.getFirst() instanceof Map<?, ?> choice)
                || !(choice.get("message") instanceof Map<?, ?> message)
                || !(message.get("content") instanceof String text))
            throw new ClassificationProviderException(ClassificationProviderException.Kind.INVALID_RESPONSE);
        return text;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
