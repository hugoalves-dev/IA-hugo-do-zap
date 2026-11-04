package dev.hugo.watcher.ai;

import dev.hugo.watcher.OperationalState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Locale;

/** Explicit, confirmed calibration against one sanitized fixture; it cannot enqueue any action. */
@Service
public final class PromptCalibrationService {
    private final ClassificationPromptService prompts;
    private final AiClassificationProperties properties;
    private final BudgetService budgets;
    private final OperationalState operational;
    private final Clock clock;
    private final ProviderTransport transport;

    @Autowired
    public PromptCalibrationService(ClassificationPromptService prompts, AiClassificationProperties properties,
                                    BudgetService budgets, OperationalState operational, Clock clock) {
        this(prompts, properties, budgets, operational, clock, new JdkProviderTransport());
    }

    PromptCalibrationService(ClassificationPromptService prompts, AiClassificationProperties properties,
                             BudgetService budgets, OperationalState operational, Clock clock,
                             ProviderTransport transport) {
        this.prompts = prompts;
        this.properties = properties;
        this.budgets = budgets;
        this.operational = operational;
        this.clock = clock;
        this.transport = transport;
    }

    public String run(int revision) {
        ClassificationPromptSnapshot prompt = prompts.forRevision(revision);
        List<String> blockers = blockers();
        if (!blockers.isEmpty()) return blocked(prompt, blockers);
        ClassificationInput fixture = ClassificationInput.sanitizedFixture(List.of(
                new ClassificationInput.ContextEntry("owner", "Uma versão nova do APK foi testada?"),
                new ClassificationInput.ContextEntry("contact", "Não foi."),
                new ClassificationInput.ContextEntry("contact", "Está para testar ainda.")));
        ProviderAuthorization authorization = (provider, input) -> authorized();
        ProviderTransport authorizedTransport;
        authorizedTransport = new BudgetedProviderTransport(transport, budgets, this::authorized,
                "openrouter", properties.attemptHoldMicros(), clock);
        try {
            ClassificationResult result = new OpenRouterProvider(
                    URI.create("https://openrouter.ai/api/v1/chat/completions"), properties.openRouterModel(),
                    properties.openRouterApiKey(), authorizedTransport, authorization, properties.timeout(),
                    properties.openRouterMaxTokens(), properties.openRouterReasoningEnabled(), prompt.systemText())
                    .classify(fixture);
            String triage = result.status() != ClassificationResult.Status.CLASSIFIED ? "UNDECIDED"
                    : !result.notificationRecommended() ? "IGNORE"
                    : result.replyRecommended() ? "NOTIFY_AND_REPLY" : "NOTIFY_ONLY";
            return "Calibração sintética do prompt v" + prompt.revision() + " · SHA-256 " + prompt.sha256().substring(0,12)
                    + "\nContexto do proprietário: uma pergunta recente sobre teste do APK."
                    + "\nAlvo sintético: resposta curta de que ainda não foi testado."
                    + "\nClassificação: " + result.status() + " / " + result.intent() + " / " + result.urgency()
                    + " / confiança " + String.format(Locale.ROOT, "%.2f", result.confidence())
                    + "\nrequestDetected=" + result.requestDetected() + "; notificationRecommended=" + result.notificationRecommended()
                    + "; replyRecommended=" + result.replyRecommended() + "; evidências=" + result.evidence()
                    + "\nTriagem simulada: " + triage + " (nenhuma notificação ou resposta foi enviada)."
                    + "\nGates: Shadow ligado; kill switch/emergência foram revalidados; orçamento local aplicado conforme ambiente."
                    + "\nAutorização, consentimento, horário e política de um contato real não foram avaliados pelo fixture.";
        } catch (BudgetExceededException exception) {
            return blocked(prompt, List.of("local_budget_exhausted"));
        } catch (ClassificationProviderException exception) {
            return blocked(prompt, List.of("provider_" + exception.kind().name().toLowerCase(Locale.ROOT)));
        }
    }

    private List<String> blockers() {
        if (operational == null || !operational.simulation()) return List.of("shadow_disabled_by_environment_or_pause");
        if (!operational.classification()) return List.of("classification_disabled_or_emergency");
        if (!properties.configured()) return List.of("ai_configuration_incomplete");
        if (properties.disableLocalBudget() || properties.attemptHoldMicros() <= 0)
            return List.of("local_budget_disabled");
        return List.of();
    }

    private boolean authorized() {
        return operational != null && operational.simulation() && operational.classification() && properties.configured();
    }

    private String blocked(ClassificationPromptSnapshot prompt, List<String> blockers) {
        return "Calibração do prompt v" + prompt.revision() + " não executada. Bloqueios: " + String.join(", ", blockers)
                + ". Nenhum provedor foi chamado; nenhum dado de conversa real foi usado.";
    }
}
