package dev.hugo.watcher.ai;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;

abstract class AbstractHttpClassificationProvider implements ClassificationProvider {
    static final int MAX_RESPONSE_BYTES = 64 * 1024;
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String SYSTEM_PROMPT = ClassificationPromptService.DEFAULT_OPERATIONAL_PROMPT + "\n\n"
            + ClassificationPromptService.IMMUTABLE_CORE;
    static final Map<String, Object> SCHEMA = Map.of(
            "type", "object", "additionalProperties", false,
            "properties", Map.of(
                    "intent", Map.of("type", "string", "enum", List.of("NONE", "CONTACT_REQUEST", "GROUP_TRIGGER", "UNCERTAIN")),
                    "requestDetected", Map.of("type", "boolean"),
                    "notificationRecommended", Map.of("type", "boolean"),
                    "replyRecommended", Map.of("type", "boolean"),
                    "urgency", Map.of("type", "string", "enum", List.of("LOW", "NORMAL", "HIGH", "CRITICAL", "UNCERTAIN")),
                    "evidence", Map.of("type", "array", "items", Map.of("type", "string", "enum",
                            List.of("DIRECT_QUESTION", "EXPLICIT_REQUEST", "CONTEXTUAL_DEPENDENCY", "EXPLICIT_DEADLINE",
                                    "CRITICAL_RISK", "VERIFIED_GROUP_TRIGGER", "PROMOTIONAL_CONTENT", "ALREADY_RESOLVED", "NO_ACTION_NEEDED")), "maxItems", 3),
                    "confidence", Map.of("type", "number", "minimum", 0, "maximum", 1)),
            "required", List.of("intent", "requestDetected", "notificationRecommended", "replyRecommended",
                    "urgency", "evidence", "confidence"));

    final String providerName;
    final ProviderTransport transport;
    final ProviderAuthorization authorization;
    final Duration timeout;
    final String systemPrompt;

    AbstractHttpClassificationProvider(String providerName, ProviderTransport transport,
                                       ProviderAuthorization authorization, Duration timeout) {
        this(providerName, transport, authorization, timeout, SYSTEM_PROMPT);
    }

    AbstractHttpClassificationProvider(String providerName, ProviderTransport transport,
                                       ProviderAuthorization authorization, Duration timeout, String systemPrompt) {
        this.providerName = providerName;
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
        this.authorization = java.util.Objects.requireNonNull(authorization, "authorization");
        this.timeout = java.util.Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        if (systemPrompt == null || systemPrompt.isBlank() || systemPrompt.length() > 12_000)
            throw new IllegalArgumentException("system prompt is invalid");
        this.systemPrompt = systemPrompt;
    }

    @Override public final String name() { return providerName; }

    final void authorize(ClassificationInput input) {
        try {
            if (!authorization.authorize(providerName, input))
                throw ClassificationProviderException.authorizationDenied(providerName);
        } catch (ClassificationProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw ClassificationProviderException.authorizationDenied(providerName);
        }
    }

    final ProviderTransport.Response send(HttpRequest request) {
        try {
            ProviderTransport.Response response = transport.send(request, timeout, MAX_RESPONSE_BYTES);
            if (response == null || response.body() == null)
                throw new ClassificationProviderException(ClassificationProviderException.Kind.INVALID_RESPONSE);
            if (response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES)
                throw new ClassificationProviderException(ClassificationProviderException.Kind.RESPONSE_TOO_LARGE);
            return response;
        } catch (ClassificationProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ClassificationProviderException(ClassificationProviderException.Kind.TECHNICAL_FAILURE, exception);
        }
    }

    final Map<?, ?> parseObject(String raw) {
        try {
            Object value = JSON.readValue(raw, Object.class);
            if (value instanceof Map<?, ?> map) return map;
        } catch (JacksonException ignored) { }
        throw new ClassificationProviderException(ClassificationProviderException.Kind.INVALID_RESPONSE);
    }

    final ClassificationResult parseClassification(String raw) {
        Map<?, ?> value = parseObject(raw);
        if (value.size() != 7 || !(value.get("intent") instanceof String intent)
                || !(value.get("requestDetected") instanceof Boolean request)
                || !(value.get("notificationRecommended") instanceof Boolean notification)
                || !(value.get("replyRecommended") instanceof Boolean reply)
                || !(value.get("urgency") instanceof String urgency)
                || !(value.get("evidence") instanceof List<?> evidence)
                || evidence.size() > 3 || !(value.get("confidence") instanceof Number confidence)
                || evidence.stream().anyMatch(item -> !(item instanceof String))) {
            throw new ClassificationProviderException(ClassificationProviderException.Kind.INVALID_RESPONSE);
        }
        try {
            ClassificationResult.Intent parsedIntent = ClassificationResult.Intent.valueOf(intent);
            ClassificationResult.Urgency parsedUrgency = ClassificationResult.Urgency.valueOf(urgency);
            List<ClassificationResult.Evidence> parsedEvidence = evidence.stream()
                    .map(String.class::cast).map(ClassificationResult.Evidence::valueOf).distinct().toList();
            if ((parsedIntent == ClassificationResult.Intent.NONE && request)
                    || (parsedIntent != ClassificationResult.Intent.NONE
                    && parsedIntent != ClassificationResult.Intent.UNCERTAIN && !request)
                    || (reply && (!notification || !request)))
                throw new IllegalArgumentException("inconsistent classification fields");
            return ClassificationResult.classified(parsedIntent, request, notification, reply,
                    parsedUrgency, parsedEvidence, confidence.doubleValue());
        } catch (IllegalArgumentException exception) {
            throw new ClassificationProviderException(ClassificationProviderException.Kind.INVALID_RESPONSE, exception);
        }
    }

    final String userPayload(ClassificationInput input) {
        try { return JSON.writeValueAsString(Map.of("target", input.targetEntry(), "context", input.contextEntries())); }
        catch (JacksonException exception) { throw new ClassificationProviderException(ClassificationProviderException.Kind.INVALID_RESPONSE, exception); }
    }

    final void validateStatus(int status, int privacyUnavailableStatus) {
        if (status >= 200 && status < 300) return;
        if (status == privacyUnavailableStatus)
            throw new ClassificationProviderException(ClassificationProviderException.Kind.PRIVACY_ROUTE_UNAVAILABLE);
        if (status == 408) throw new ClassificationProviderException(ClassificationProviderException.Kind.TIMEOUT);
        if (status == 429 || status >= 500)
            throw new ClassificationProviderException(ClassificationProviderException.Kind.TECHNICAL_FAILURE);
        throw new ClassificationProviderException(ClassificationProviderException.Kind.CONFIGURATION_ERROR);
    }
}
