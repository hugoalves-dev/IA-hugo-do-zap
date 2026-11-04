package dev.hugo.watcher.ai;

import org.springframework.stereotype.Component;
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
        if (!gate.allowed() || !properties.configured() || properties.disableLocalBudget()
                || properties.attemptHoldMicros() <= 0)
            throw new IllegalStateException("prompt_generation_unavailable");
        if (operationalPrompt == null || operationalPrompt.isBlank() || explicitInstructions == null
                || explicitInstructions.isBlank() || explicitInstructions.length() > 4096)
            throw new IllegalArgumentException("invalid_prompt_instructions");
        ProviderTransport budgeted = new BudgetedProviderTransport(transport, budgets,
                gate::allowed, "openrouter", properties.attemptHoldMicros(), clock);
        try {
            String body = JSON.writeValueAsString(Map.of(
                    "model", properties.openRouterModel(),
                    "temperature", 0.2,
                    "provider", Map.of("zdr", true, "data_collection", "deny", "allow_fallbacks", false,
                            "require_parameters", true),
                    "max_tokens", MAX_OUTPUT_TOKENS,
                    "messages", List.of(
                            Map.of("role", "system", "content", "Propose operational classification guidance only. Preserve all safety and schema constraints. Return the exact JSON schema; no commentary."),
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
            if (response == null || response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("prompt_generation_provider_failure");
            Map<?, ?> root = JSON.readValue(response.body(), Map.class);
            List<?> choices = (List<?>) root.get("choices");
            Map<?, ?> message = (Map<?, ?>) choices.getFirst();
            Map<?, ?> content = (Map<?, ?>) message.get("message");
            Map<?, ?> proposal = JSON.readValue(String.valueOf(content.get("content")), Map.class);
            if (proposal.size() != 2 || !(proposal.get("prompt") instanceof String prompt)
                    || !(proposal.get("summary") instanceof String summary))
                throw new IllegalStateException("invalid_prompt_generation_response");
            return new Proposal(prompt, summary);
        } catch (JacksonException | ClassCastException | IndexOutOfBoundsException ex) {
            throw new IllegalStateException("invalid_prompt_generation_response");
        }
    }

    public record Proposal(String prompt, String summary) { }
    @FunctionalInterface public interface BooleanSupplierGate { boolean allowed(); }
}
