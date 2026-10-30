package dev.hugo.watcher.ai;

import tools.jackson.core.JacksonException;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class OpenRouterProvider extends AbstractHttpClassificationProvider {
    private final URI endpoint;
    private final String model;
    private final String apiKey;
    private final int maxTokens;
    private final boolean reasoningEnabled;

    public OpenRouterProvider(URI endpoint, String model, String apiKey, ProviderTransport transport,
                              ProviderAuthorization authorization, Duration timeout) {
        this(endpoint, model, apiKey, transport, authorization, timeout, 300, false);
    }

    public OpenRouterProvider(URI endpoint, String model, String apiKey, ProviderTransport transport,
                              ProviderAuthorization authorization, Duration timeout,
                              int maxTokens, boolean reasoningEnabled) {
        super("openrouter", transport, authorization, timeout);
        this.endpoint = ProviderEndpointPolicy.requireSafe(endpoint, "openrouter");
        this.model = requireText(model, "model");
        this.apiKey = requireText(apiKey, "apiKey");
        if (maxTokens < 128 || maxTokens > 8192)
            throw new IllegalArgumentException("OpenRouter max_tokens must be between 128 and 8192");
        this.maxTokens = maxTokens;
        this.reasoningEnabled = reasoningEnabled;
    }

    @Override public ClassificationResult classify(ClassificationInput input) {
        java.util.Objects.requireNonNull(input, "input");
        if (input.messages().isEmpty()) return ClassificationResult.uncertain();
        authorize(input);
        ProviderTransport.Response response = send(request(input));
        validateStatus(response.statusCode(), 404);
        return parseClassification(extractText(response.body()));
    }

    private HttpRequest request(ClassificationInput input) {
        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", userPayload(input))),
                "max_tokens", maxTokens,
                "provider", Map.of("zdr", true, "data_collection", "deny", "allow_fallbacks", false,
                        "require_parameters", true),
                "reasoning", Map.of("enabled", reasoningEnabled),
                "response_format", Map.of("type", "json_schema", "json_schema", Map.of(
                        "name", "classification", "strict", true, "schema", SCHEMA))));
        if (input.batchId() != null) body.put("trace", Map.of("batch_id", input.batchId().toString()));
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
        if (!(choices instanceof List<?> list) || list.isEmpty() || !(list.getFirst() instanceof Map<?, ?> choice))
            throw new ClassificationProviderException(ClassificationProviderException.Kind.INVALID_RESPONSE);
        // A later choice must never hide a truncated response behind a valid first choice.
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> candidate))
                throw new ClassificationProviderException(ClassificationProviderException.Kind.INVALID_RESPONSE);
            Object finish = candidate.get("finish_reason");
            if ("length".equals(finish) || "max_tokens".equals(finish))
                throw new ClassificationProviderException(ClassificationProviderException.Kind.OUTPUT_TRUNCATED);
            if (finish != null && !"stop".equals(finish) && !"end_turn".equals(finish))
                throw new ClassificationProviderException(ClassificationProviderException.Kind.INVALID_RESPONSE);
        }
        if (!(choice.get("message") instanceof Map<?, ?> message)
                || !(message.get("content") instanceof String text) || text.isBlank())
            throw new ClassificationProviderException(ClassificationProviderException.Kind.INVALID_RESPONSE);
        return text;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
