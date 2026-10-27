package dev.hugo.watcher.ai;

import tools.jackson.core.JacksonException;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class GeminiProvider extends AbstractHttpClassificationProvider {
    private final URI endpoint;
    private final String model;
    private final String apiKey;

    public GeminiProvider(URI endpoint, String model, String apiKey, ProviderTransport transport,
                          ProviderAuthorization authorization, Duration timeout) {
        super("gemini", transport, authorization, timeout);
        this.endpoint = ProviderEndpointPolicy.requireSafe(endpoint, "gemini");
        this.model = requireText(model, "model");
        this.apiKey = requireText(apiKey, "apiKey");
    }

    @Override public ClassificationResult classify(ClassificationInput input) {
        java.util.Objects.requireNonNull(input, "input");
        if (input.messages().isEmpty()) return ClassificationResult.uncertain();
        for (int attempt = 0; attempt < 2; attempt++) {
            authorize(input);
            ProviderTransport.Response response = send(request(input));
            validateStatus(response.statusCode(), 0);
            try { return parseClassification(extractText(response.body())); }
            catch (ClassificationProviderException exception) {
                if (exception.kind() != ClassificationProviderException.Kind.INVALID_RESPONSE || attempt == 1) throw exception;
            }
        }
        return ClassificationResult.uncertain();
    }

    private HttpRequest request(ClassificationInput input) {
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", SYSTEM_PROMPT))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userPayload(input))))),
                "generationConfig", Map.of("responseMimeType", "application/json", "responseSchema", SCHEMA,
                        "temperature", 0, "maxOutputTokens", 300));
        try {
            String path = endpoint.toString().replaceAll("/$", "") + "/" + model + ":generateContent";
            return HttpRequest.newBuilder(URI.create(path)).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new ClassificationProviderException(ClassificationProviderException.Kind.CONFIGURATION_ERROR, exception);
        }
    }

    private String extractText(String body) {
        Map<?, ?> root = parseObject(body);
        Object candidates = root.get("candidates");
        if (!(candidates instanceof List<?> list) || list.isEmpty() || !(list.getFirst() instanceof Map<?, ?> candidate)
                || !(candidate.get("content") instanceof Map<?, ?> content)
                || !(content.get("parts") instanceof List<?> parts) || parts.isEmpty()
                || !(parts.getFirst() instanceof Map<?, ?> part) || !(part.get("text") instanceof String text))
            throw new ClassificationProviderException(ClassificationProviderException.Kind.INVALID_RESPONSE);
        return text;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
