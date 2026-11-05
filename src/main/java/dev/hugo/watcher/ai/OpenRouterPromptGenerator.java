package dev.hugo.watcher.ai;

import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/** OpenRouter-only constrained prompt authoring boundary. */
@Component
public final class OpenRouterPromptGenerator {
    public static final int MAX_OUTPUT_TOKENS = 2048;
    private static final Logger log = LoggerFactory.getLogger(OpenRouterPromptGenerator.class);
    private static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/chat/completions");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final AiClassificationProperties properties;
    private final ProviderTransport transport;
    private final BudgetService budgets;
    private final Clock clock;

    @Autowired
    public OpenRouterPromptGenerator(AiClassificationProperties properties, BudgetService budgets, Clock clock) {
        this(properties, new JdkProviderTransport(), budgets, clock);
    }

    OpenRouterPromptGenerator(AiClassificationProperties properties, ProviderTransport transport,
                              BudgetService budgets, Clock clock) {
        this.properties = properties;
        this.transport = transport;
        this.budgets = budgets;
        this.clock = clock;
    }

    public Proposal generate(String operationalPrompt, String explicitInstructions, BooleanSupplierGate gate) {
        return generate(operationalPrompt, explicitInstructions, gate, null);
    }

    public Proposal generate(String operationalPrompt, String explicitInstructions, BooleanSupplierGate gate,
                             PromptGenerationContext context) {
        if (gate == null || !gate.allowed()) throw failure(PromptGenerationFailure.Stage.PREFLIGHT,
                PromptGenerationFailure.ReasonCode.SHADOW_PAUSED);
        if (properties == null || !properties.configured()) throw failure(PromptGenerationFailure.Stage.PREFLIGHT,
                PromptGenerationFailure.ReasonCode.CONFIG_INVALID);
        if (properties.disableLocalBudget()) throw failure(PromptGenerationFailure.Stage.PREFLIGHT,
                PromptGenerationFailure.ReasonCode.BUDGET_MODE_BLOCKED);
        if (properties.attemptHoldMicros() <= 0) throw failure(PromptGenerationFailure.Stage.PREFLIGHT,
                PromptGenerationFailure.ReasonCode.CONFIG_INVALID);
        if (operationalPrompt == null || operationalPrompt.isBlank() || explicitInstructions == null
                || explicitInstructions.isBlank() || explicitInstructions.length() > 4096)
            throw failure(PromptGenerationFailure.Stage.PREFLIGHT, PromptGenerationFailure.ReasonCode.INVALID_INSTRUCTIONS);
        ProviderTransport budgeted = new BudgetedProviderTransport(transport, budgets,
                gate::allowed, "openrouter", properties.attemptHoldMicros(), clock, context);
        try {
            String body = JSON.writeValueAsString(Map.of(
                    "model", properties.openRouterModel(),
                    "provider", Map.of("zdr", true, "data_collection", "deny", "allow_fallbacks", false,
                            "require_parameters", true),
                    "max_tokens", MAX_OUTPUT_TOKENS,
                    "messages", List.of(
                            Map.of("role", "system", "content", "Propose changes only to semantic classification guidance (intent, request detection, urgency, response recommendation). " +
                                    "Contact markers, working hours, read/unread state, priority, notification routing, automatic reply permissions and templates are OUT OF SCOPE. " +
                                    "Never claim or implement a policy change by editing a prompt. If no classification improvement is possible, return the original prompt unchanged. " +
                                    "Preserve all safety and schema constraints. Return the exact JSON schema; no commentary."),
                            Map.of("role", "user", "content", JSON.writeValueAsString(Map.of(
                                    "currentOperationalPrompt", operationalPrompt,
                                    "adminInstructions", explicitInstructions)))),
                    "response_format", Map.of("type", "json_schema", "json_schema", Map.of(
                            "name", "prompt_proposal", "strict", true,
                            "schema", Map.of("type", "object", "additionalProperties", false,
                                    "properties", Map.of("prompt", Map.of("type", "string"),
                                            "summary", Map.of("type", "string")),
                                    "required", List.of("prompt", "summary"))))));
            HttpRequest request = HttpRequest.newBuilder(ProviderEndpointPolicy.requireSafe(ENDPOINT, "openrouter"))
                    .timeout(properties.timeout()).header("Authorization", "Bearer " + properties.openRouterApiKey())
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            ProviderTransport.Response response = budgeted.send(request, properties.timeout(), 64 * 1024);
            if (response == null || response.statusCode() < 200 || response.statusCode() >= 300) {
                logRejectedResponse(response, context);
                throw failure(PromptGenerationFailure.Stage.PROVIDER, PromptGenerationFailure.ReasonCode.PROVIDER_FAILURE);
            }
            Map<?, ?> root = JSON.readValue(response.body(), Map.class);
            List<?> choices = (List<?>) root.get("choices");
            Map<?, ?> message = (Map<?, ?>) choices.getFirst();
            Map<?, ?> content = (Map<?, ?>) message.get("message");
            Map<?, ?> proposal = JSON.readValue(String.valueOf(content.get("content")), Map.class);
            if (proposal.size() != 2 || !(proposal.get("prompt") instanceof String prompt)
                    || !(proposal.get("summary") instanceof String summary))
                throw failure(PromptGenerationFailure.Stage.PROVIDER,
                        PromptGenerationFailure.ReasonCode.INVALID_PROVIDER_OUTPUT);
            return new Proposal(prompt, summary);
        } catch (PromptGenerationFailure failure) {
            throw failure;
        } catch (BudgetExceededException failure) {
            throw failure(PromptGenerationFailure.Stage.PROVIDER, PromptGenerationFailure.ReasonCode.BUDGET_EXHAUSTED);
        } catch (JacksonException | ClassCastException | IndexOutOfBoundsException | NullPointerException exception) {
            throw failure(PromptGenerationFailure.Stage.PROVIDER,
                    PromptGenerationFailure.ReasonCode.INVALID_PROVIDER_OUTPUT);
        } catch (RuntimeException exception) {
            throw failure(PromptGenerationFailure.Stage.PROVIDER, PromptGenerationFailure.ReasonCode.PROVIDER_FAILURE);
        }
    }

    /**
     * Only allowlisted, bounded diagnostics from untrusted provider errors reach the logs.
     * Never log the provider's message, response body, arbitrary metadata or exception text.
     */
    private void logRejectedResponse(ProviderTransport.Response response, PromptGenerationContext context) {
        var diagnostic = SafeError.fromBody(response == null ? null : response.body());
        log.warn("prompt_generation_provider event=HTTP_REJECTED jobId={} operation={} provider=openrouter " +
                        "model={} httpStatus={} maxTokens={} errorCode={} errorType={} failedRoutingStep={}",
                context == null ? "NONE" : context.jobId(),
                context == null ? "UNKNOWN" : context.operation(),
                properties.openRouterModel(),
                response == null ? "NONE" : response.statusCode(),
                MAX_OUTPUT_TOKENS,
                diagnostic.code(), diagnostic.type(), diagnostic.failedRoutingStep());
    }

    private record SafeError(String code, String type, String failedRoutingStep) {
        private static final SafeError UNKNOWN = new SafeError("UNKNOWN", "UNKNOWN", "UNKNOWN");

        static SafeError fromBody(String body) {
            if (body == null || body.isBlank()) return UNKNOWN;
            try {
                Object decoded = JSON.readValue(body, Object.class);
                if (!(decoded instanceof Map<?, ?> root)
                        || !(root.get("error") instanceof Map<?, ?> error)) return UNKNOWN;
                Object metadata = error.get("metadata");
                Object routingStep = metadata instanceof Map<?, ?> details
                        ? details.get("failed_routing_step") : null;
                return new SafeError(code(error.get("code")), type(error.get("type")), routingStep(routingStep));
            } catch (RuntimeException ignored) {
                // Diagnostic parsing must never change the existing fail-closed provider outcome.
                return UNKNOWN;
            }
        }

        private static String code(Object raw) {
            if (raw instanceof Number number) {
                long value = number.longValue();
                return value >= 100 && value <= 599 && number.doubleValue() == value
                        ? Long.toString(value) : "UNKNOWN";
            }
            if (!(raw instanceof String value)) return "UNKNOWN";
            return switch (value) {
                case "400", "401", "402", "403", "404", "408", "409", "413", "422", "429",
                     "500", "502", "503", "504" -> value;
                case "model_not_found", "no_endpoints_found", "no_endpoint_found",
                     "invalid_request", "unsupported_parameters", "rate_limit_exceeded" -> value;
                default -> "UNKNOWN";
            };
        }

        private static String type(Object raw) {
            if (!(raw instanceof String value)) return "UNKNOWN";
            return switch (value) {
                case "invalid_request_error", "authentication_error", "permission_error",
                     "not_found_error", "rate_limit_error", "overloaded_error",
                     "api_error", "server_error", "routing_error", "provider_error" -> value;
                default -> "UNKNOWN";
            };
        }

        private static String routingStep(Object raw) {
            if (!(raw instanceof String value)) return "UNKNOWN";
            return switch (value) {
                case "Filter by Allowed Providers" -> "FILTER_BY_ALLOWED_PROVIDERS";
                case "Filter by Required Parameters" -> "FILTER_BY_REQUIRED_PARAMETERS";
                case "Filter by ZDR" -> "FILTER_BY_ZDR";
                case "Filter by Data Collection" -> "FILTER_BY_DATA_COLLECTION";
                case "Filter by Max Price" -> "FILTER_BY_MAX_PRICE";
                case "Filter by Context Length" -> "FILTER_BY_CONTEXT_LENGTH";
                case "Filter by Regional Surcharge" -> "FILTER_BY_REGIONAL_SURCHARGE";
                case "Filter by Provider Availability" -> "FILTER_BY_PROVIDER_AVAILABILITY";
                default -> "UNKNOWN";
            };
        }
    }

    private static PromptGenerationFailure failure(PromptGenerationFailure.Stage stage,
                                                   PromptGenerationFailure.ReasonCode reason) {
        return new PromptGenerationFailure(stage, reason);
    }

    public record Proposal(String prompt, String summary) { }
    @FunctionalInterface public interface BooleanSupplierGate { boolean allowed(); }
}
